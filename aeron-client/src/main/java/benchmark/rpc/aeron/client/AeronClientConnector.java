package benchmark.rpc.aeron.client;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.agrona.concurrent.BackoffIdleStrategy;

import benchmark.rpc.protocol.Request;
import benchmark.rpc.protocol.Response;
import benchmark.service.ServiceRegister;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;

public class AeronClientConnector implements ResponseCallback, Closeable {

	private final FutureContainer futureContainer = new FutureContainer();
	private final long defaultTimeout = 10_000L;

	private volatile MediaDriver mediaDriver;
	private volatile Publisher publisher;
	private volatile Subscriber subscriber;

	private static final MediaDriver.Context ctx = new MediaDriver.Context()//
			.termBufferSparseFile(false)//
			.threadingMode(ThreadingMode.DEDICATED)//
			.conductorIdleStrategy(new BackoffIdleStrategy(1000, 100, TimeUnit.MICROSECONDS.toNanos(1),
					TimeUnit.MICROSECONDS.toNanos(100)))//
			.receiverIdleStrategy(new BackoffIdleStrategy(1000, 100, TimeUnit.MICROSECONDS.toNanos(1),
					TimeUnit.MICROSECONDS.toNanos(100)))//
			.senderIdleStrategy(new BackoffIdleStrategy(1000, 100, TimeUnit.MICROSECONDS.toNanos(1),
					TimeUnit.MICROSECONDS.toNanos(100)));

	public void connect() {
		//mediaDriver = MediaDriver.launch(ctx);

		publisher = new Publisher();
		publisher.connect();

		subscriber = new Subscriber(this);
		subscriber.connect();
	}

	@Override
	public void close() throws IOException {
		try {
			//mediaDriver.close();
		} catch (Exception e1) {
		}

		try {
			publisher.close();
		} catch (Exception e) {
			e.printStackTrace();
		}

		try {
			subscriber.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onResponse(Response response) {
		futureContainer.notifyResponse(response);
	}

	public Response execute(Request request) throws InterruptedException, ExecutionException, TimeoutException {
		return execute(request, defaultTimeout, TimeUnit.MILLISECONDS);
	}

	public Response execute(Request request, long timeout, TimeUnit unit)
			throws InterruptedException, ExecutionException, TimeoutException {

		final long requestId = request.getRequestId();
		final CallerBlockingFuture<Response> future = new CallerBlockingFuture<>();

		futureContainer.addFuture(requestId, future);

		try {
			publisher.publish(request, timeout, unit);
			return future.get(timeout, unit);
		} finally {
			// 防止内存溢出
			futureContainer.remove(requestId);
		}
	}

	public static void main(String[] args) throws Exception {

		try (AeronClientConnector clientConnector = new AeronClientConnector();) {
			clientConnector.connect();

			long requestId = 0;

			while (true) {
				Request request = new Request();
				request.setRequestId(requestId++);
				request.setServiceId(ServiceRegister.GET_USER);
				request.setParams(new Object[] { 0L });

				Response response = clientConnector.execute(request);

				System.out.println(response);

				break;
			}
		}
	}

}
