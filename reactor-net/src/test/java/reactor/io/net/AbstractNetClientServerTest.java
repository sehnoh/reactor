package reactor.io.net;

import com.gs.collections.api.RichIterable;
import com.gs.collections.impl.list.mutable.FastList;
import org.junit.After;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.Environment;
import reactor.core.support.NamedDaemonThreadFactory;
import reactor.fn.Predicate;
import reactor.io.buffer.Buffer;
import reactor.io.codec.Codec;
import reactor.io.codec.StandardCodecs;
import reactor.io.net.tcp.TcpClient;
import reactor.io.net.tcp.TcpServer;
import reactor.io.net.tcp.support.SocketUtils;
import reactor.rx.Promise;
import reactor.rx.Promises;
import reactor.rx.Streams;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @author Jon Brisbin
 */
public class AbstractNetClientServerTest {

	public static final String LOCALHOST = "127.0.0.1";

	private static final Random random = new Random(System.nanoTime());
	private static final String CHARS  = "AaBbCcDdEeFfGgHhIiJjKkLlMmNnOoPpQqRrSsTtUuVvWwXxYyZz0123456789";

	protected final Logger log = LoggerFactory.getLogger(getClass());

	private final int senderThreads = Environment.PROCESSORS;
	protected Data            data;
	private   ExecutorService serverPool;
	private   ExecutorService clientPool;
	private   Environment     env1;
	private   Environment     env2;
	private   int             port;

	protected static Data generateData() {
		char[] name = new char[16];
		for (int i = 0; i < name.length; i++) {
			name[i] = CHARS.charAt(random.nextInt(CHARS.length()));
		}
		return new Data(random.nextInt(), random.nextLong(), new String(name));
	}

	protected static <IN, OUT> ChannelStream<IN, OUT> assertClientStarted(Client<IN, OUT, ?> client)
			throws InterruptedException {
		ChannelStream<IN, OUT> ch = client.open().await(5, TimeUnit.SECONDS);
		assertNotNull(client.getClass().getSimpleName() + " was started", ch);
		return ch;
	}

	protected static <IN, OUT> void assertClientStopped(Client<IN, OUT, ?> client)
			throws InterruptedException {
		Promise<Void> closed = client.close();
		closed.await(1, TimeUnit.SECONDS);
		assertTrue(client.getClass().getSimpleName() + " was stopped", closed.isSuccess());
	}


	protected static <IN, OUT> void assertServerStarted(Server<IN, OUT, ?> server) throws InterruptedException {
		Promise<Void> started = server.start();
		started.await(5, TimeUnit.SECONDS);
		assertTrue(server.getClass().getSimpleName() + " was started", started.isSuccess());
	}

	protected static <IN, OUT> void assertServerStopped(Server<IN, OUT, ?> server) throws InterruptedException {
		Promise<Void> started = server.shutdown();
		started.await(1, TimeUnit.SECONDS);
		assertTrue(server.getClass().getSimpleName() + " was stopped", started.isSuccess());
	}

	@Before
	public void setup() {
		clientPool = Executors.newCachedThreadPool(new NamedDaemonThreadFactory(getClass().getSimpleName() + "-server"));
		serverPool = Executors.newCachedThreadPool(new NamedDaemonThreadFactory(getClass().getSimpleName() + "-client"));

		env1 = new Environment();
		env2 = new Environment();

		port = SocketUtils.findAvailableTcpPort();

		data = generateData();
	}

	@After
	public void cleanup() throws InterruptedException {
		//env1.shutdown();
		//env2.shutdown();

		clientPool.shutdownNow();
		clientPool.awaitTermination(5, TimeUnit.SECONDS);

		serverPool.shutdownNow();
		serverPool.awaitTermination(5, TimeUnit.SECONDS);
	}

	protected int getPort() {
		return port;
	}

	protected <T> Spec.TcpServer<T, T> createTcpServer(Class<? extends reactor.io.net.tcp.TcpServer> type,
	                                                  Class<? extends T> dataType) {
		return createTcpServer(type, dataType, dataType);
	}

	protected <IN, OUT> Spec.TcpServer<IN, OUT> createTcpServer(Class<? extends reactor.io.net.tcp.TcpServer> type,
	                                                           Class<? extends IN> inType,
	                                                           Class<? extends OUT> outType) {
		return new Spec.TcpServer<IN, OUT>(type).env(env1).dispatcher("sync").listen(LOCALHOST, port);
	}

	protected <T> Spec.TcpClient<T, T> createTcpClient(Class<? extends reactor.io.net.tcp.TcpClient> type,
	                                                  Class<? extends T> dataType) {
		return createTcpClient(type, dataType, dataType);
	}

	protected <IN, OUT> Spec.TcpClient<IN, OUT> createTcpClient(Class<? extends reactor.io.net.tcp.TcpClient> type,
	                                                           Class<? extends IN> inType,
	                                                           Class<? extends OUT> outType) {
		return new Spec.TcpClient<IN, OUT>(type).env(env2).dispatcher("sync").connect(LOCALHOST, port);
	}

	protected <T> void assertTcpClientServerExchangedData(Class<? extends reactor.io.net.tcp.TcpServer> serverType,
	                                                      Class<? extends reactor.io.net.tcp.TcpClient> clientType,
	                                                      Buffer data) throws InterruptedException {
		assertTcpClientServerExchangedData(
				serverType,
				clientType,
				StandardCodecs.PASS_THROUGH_CODEC,
				data,
				(Buffer b) -> {
					byte[] b1 = data.flip().asBytes();
					byte[] b2 = b.asBytes();
					return Arrays.equals(b1, b2);
				}
		);
	}

	@SuppressWarnings("unchecked")
	protected <T> void assertTcpClientServerExchangedData(Class<? extends reactor.io.net.tcp.TcpServer> serverType,
	                                                      Class<? extends reactor.io.net.tcp.TcpClient> clientType,
	                                                      Codec<Buffer, T, T> codec,
	                                                      T data,
	                                                      Predicate<T> replyPredicate)
			throws InterruptedException {
		final Codec<Buffer, T, T> elCodec = codec == null ? (Codec<Buffer, T, T>) StandardCodecs.PASS_THROUGH_CODEC :
				codec;

		TcpServer<T, T> server = NetStreams.tcpServer(serverType, s -> s
						.env(env1)
						.listen(LOCALHOST, getPort())
						.codec(elCodec)
		);

		server.service(ch -> ch);
		assertServerStarted(server);

		TcpClient<T, T> client = NetStreams.tcpClient(clientType, s -> s
						.env(env2)
						.connect(LOCALHOST, getPort())
						.codec(elCodec)
		);

		final Promise<T> p = Promises.prepare();

		client.connect((output, input) -> {
			input.subscribe(p);
			Streams.just(data).subscribe(output);
		});

		ChannelStream<T, T> ch = assertClientStarted(client);

		T reply = p.await(5, TimeUnit.SECONDS);

		assertTrue("reply was correct", replyPredicate.test(reply));

//		assertServerStopped(server);
//		assertClientStopped(client);
	}

	protected Environment getServerEnvironment() {
		return env1;
	}

	protected Environment getClientEnvironment() {
		return env2;
	}

	protected Future<?> submitServer(Runnable r) {
		return serverPool.submit(r);
	}

	protected RichIterable<Future<?>> submitClients(Runnable r) {
		FastList<Future<?>> futures = FastList.newList();
		for (int i = 0; i < senderThreads; i++) {
			futures.add(clientPool.submit(r));
		}
		return futures.toImmutable();
	}

	protected static class Data {
		private int    count;
		private long   length;
		private String name;

		public Data() {
		}

		public Data(int count, long length, String name) {
			this.count = count;
			this.length = length;
			this.name = name;
		}

		public int getCount() {
			return count;
		}

		public long getLength() {
			return length;
		}

		public String getName() {
			return name;
		}

		@Override
		public String toString() {
			return "Data{" +
					"count=" + count +
					", length=" + length +
					", name='" + name + '\'' +
					'}';
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Data)) return false;

			Data data = (Data) o;

			if (count != data.count) return false;
			if (length != data.length) return false;
			if (!name.equals(data.name)) return false;

			return true;
		}

		@Override
		public int hashCode() {
			int result = count;
			result = 31 * result + (int) (length ^ (length >>> 32));
			result = 31 * result + name.hashCode();
			return result;
		}
	}

}
