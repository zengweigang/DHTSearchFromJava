package com.konka.dhtsearch.bittorrentkad.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.yaircc.torrent.bencoding.BEncodedInputStream;
import org.yaircc.torrent.bencoding.BMap;
import org.yaircc.torrent.bencoding.BTypeException;

import com.konka.dhtsearch.Node;
import com.konka.dhtsearch.bittorrentkad.krpc.KadMessage;
import com.konka.dhtsearch.bittorrentkad.krpc.KadRequest;
import com.konka.dhtsearch.bittorrentkad.krpc.find_node.FindNodeRequest;
import com.konka.dhtsearch.bittorrentkad.krpc.find_node.FindNodeResponse;
import com.konka.dhtsearch.bittorrentkad.krpc.get_peers.GetPeersRequest;
import com.konka.dhtsearch.bittorrentkad.krpc.ping.PingRequest;

/**
 * 守护线程，负责接受和发送消息
 * 
 */
public class KadServer implements Runnable {

	private final DatagramSocket socket;
	private final BlockingQueue<DatagramPacket> pkts = new LinkedBlockingDeque<DatagramPacket>();;
	private final ExecutorService srvExecutor = new ScheduledThreadPoolExecutor(10);
	private final AtomicBoolean isActive = new AtomicBoolean(false);

	public KadServer(DatagramSocket socket) {
		this.socket = socket;
	}

	/**
	 * 真正发送网络数据报消息
	 * 
	 * @param to
	 *            目的地的节点
	 * @param msg
	 *            要发送的消息（一般是具体实现）
	 * @throws IOException
	 *             any socket exception
	 */
	// @Override
	public void send(final Node to, final KadMessage msg) throws IOException {
		try {
			byte[] buf =msg.getBencodeData(to) ;
			final DatagramPacket pkt = new DatagramPacket(buf, 0, buf.length);

			pkt.setSocketAddress(to.getSocketAddress());
			this.socket.send(pkt);

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	// 收到信息后处理
	private void handleIncomingPacket(final DatagramPacket pkt) {
		this.srvExecutor.execute(new Runnable() {// 交给线程池处理
					@Override
					public void run() {
						KadMessage msg = null;
						try {// 这里处理消息的方法需要重写
							BMap bMap = (BMap) BEncodedInputStream.bdecode(pkt.getData());
							Node src = new Node().setInetAddress(pkt.getAddress());// InetAddress;//对方的node信息
							String transaction = bMap.getString("t");//交互用的识别id
							
							if (bMap.containsKey("y")) {
								String y = bMap.getString("y");
								
								if ("q".equals(y)) {// 对方请求
									if (bMap.containsKey("q")) {
										String q = bMap.getString("q");// find_node or getpeers===
										switch (q) {
											case "find_node":
												handleFind_NodeRequest(bMap);
												break;
											case "get_peers":
												handleGet_PeersRequest(bMap);
												break;
											case "ping"://
												hanldePingRequest(bMap);
												break;
											default:
												break;
										}
									} else {
										return;
									}
								} else if ("r".equals(y)) {// 对方的响应（由于值爬数据，不用处理太复杂）
									MessageDispatcher messageDispatcher = MessageDispatcher.findMessageDispatcherByTag(transaction);//取出之前的请求对象
									if (messageDispatcher != null) {//有记录
										KadRequest kadRequest = messageDispatcher.getKadRequest();
										if (kadRequest.getClass() == FindNodeRequest.class) {//如果我之前的请求是findnode,那么这个应该是请求的回复
											FindNodeResponse findNodeResponse=receiveFind_Node(transaction,bMap,src);
											messageDispatcher.handle(findNodeResponse);
										} else if (kadRequest.getClass() == PingRequest.class) {
											// TODO
										} else if (kadRequest.getClass() == GetPeersRequest.class) {

										} else {
											// TODO 响应的操作应该根据请求的id t判断是哪个响应，t清楚一次必须改变
										}
										messageDispatcher.handle(msg);
									}else{//没有记录就按照大众处理
//										FindNodeResponse findNodeResponse=receiveFind_Node(transaction,bMap,src);
//										messageDispatcher.handle(findNodeResponse);
									}
								}

							}

						} catch (final Exception e) {
							e.printStackTrace();
							return;
						} finally {
							KadServer.this.pkts.offer(pkt);// 如果可以，将ptk加入到队列
						}
					}

				});
	}
	/**
	 * 回复Ping请求
	 * @param bMap
	 */
	protected void hanldePingRequest(BMap bMap) {
		
	}

	/**
	 * 回复Get_Peers请求
	 * @param bMap
	 */
	protected void handleGet_PeersRequest(BMap bMap) {
		
	}

	/**
	 * 回复find_node请求
	 * @param bMap
	 */
	private void handleFind_NodeRequest(BMap bMap) {

	}
	/**
	 * 
	 * @param bMap
	 * @param src 对方node 主要是地址和端口
	 * @throws BTypeException 
	 */
	private FindNodeResponse  receiveFind_Node(String transaction,BMap bMap,Node src) throws BTypeException{
			BMap bMap_r = bMap.getMap("r");
			if (bMap_r.containsKey("nodes")) {
				List<Node> nodes = passNodes(bMap_r.getString("nodes"));
				FindNodeResponse msg1 = new FindNodeResponse(transaction, src);
				msg1.setNodes(nodes);
				// 对方的node t 还有nodes
				FindNodeResponse findNodeResponse = new FindNodeResponse(transaction, src);
				return findNodeResponse.setNodes(nodes);
			// 这里收到nodes后要将nodes插入到自己的路由表中
			} else {
				return null;
			}
//		}
	}
	/**
	 * 解析出nodes
	 * 
	 * @param string
	 *            nodes集合的字符串
	 */
	private List<Node> passNodes(String string) {
		return null;
	}

	/**
	 * The server loop:
	 * 
	 * @category accept a message from socket
	 * @category parse message
	 * @category handle the message in a thread pool 这个线程用来接受信息
	 */
	@Override
	public void run() {
		this.isActive.set(true);

		while (this.isActive.get()) {
			DatagramPacket pkt = null;
			try {
				System.out.println("等待数据");
				pkt = this.pkts.poll();

				if (pkt == null)
					pkt = new DatagramPacket(new byte[1024 * 64], 1024 * 64);

				this.socket.receive(pkt);// 堵塞
				System.out.println("已经拿到数据可");
				handleIncomingPacket(pkt);// 收到信息后处理

			} catch (final Exception e) {
				// insert the taken pkt back
				if (pkt != null)
					this.pkts.offer(pkt);

				e.printStackTrace();
			}

		}
	}

	/**
	 * Shutdown the server and closes the socket 关闭服务
	 * 
	 * @param kadServerThread
	 */
	// @Override
	public void shutdown(final Thread kadServerThread) {
		this.isActive.set(false);
		this.socket.close();
		kadServerThread.interrupt();
		try {
			kadServerThread.join();
		} catch (final InterruptedException e) {
		}
	}

}
