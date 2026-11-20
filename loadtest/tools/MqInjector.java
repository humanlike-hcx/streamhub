import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;

/**
 * 压测辅助：绕过网关，直接往 danmaku topic 里塞消息（和 RocketMqPublisher 写的 JSON 同格式）。
 * 用途：① 每条消息投递 dup 次，模拟 MQ 重复投递，检查落库幂等；② 造积压，测落库消费速度。
 * 用法：java -cp <classpath> MqInjector.java <namesrv> <topic> <videoId> <idBase> <count> <dup>
 */
public class MqInjector {
    public static void main(String[] a) throws Exception {
        String ns = a[0], topic = a[1];
        long videoId = Long.parseLong(a[2]), idBase = Long.parseLong(a[3]);
        int count = Integer.parseInt(a[4]), dup = Integer.parseInt(a[5]);
        DefaultMQProducer p = new DefaultMQProducer("streamhub-injector");
        p.setNamesrvAddr(ns);
        p.setSendMsgTimeout(10000);
        p.start();
        Semaphore window = new Semaphore(2000);           // 最多 2000 条在途，防止把客户端内存打爆
        CountDownLatch done = new CountDownLatch(count * dup);
        AtomicLong fail = new AtomicLong();
        long t0 = System.nanoTime();
        // 先把每条发一遍，再整体重发 dup-1 遍：重复消息会分散在不同批次、甚至不同队列里，更接近真实重投
        for (int r = 0; r < dup; r++) {
            for (int i = 0; i < count; i++) {
                long id = idBase + i;
                String body = "{\"id\":" + id + ",\"videoId\":" + videoId + ",\"userId\":" + (i % 1000 + 1)
                        + ",\"content\":\"inj-" + id + "\",\"color\":\"#FFFFFF\",\"playbackTime\":" + (i % 3600)
                        + ".0,\"sentAt\":" + System.currentTimeMillis() + "}";
                window.acquire();
                p.send(new Message(topic, "danmaku", Long.toString(id), body.getBytes()), new SendCallback() {
                    public void onSuccess(SendResult s) { window.release(); done.countDown(); }
                    public void onException(Throwable e) { fail.incrementAndGet(); window.release(); done.countDown(); }
                });
            }
        }
        done.await();
        double sec = (System.nanoTime() - t0) / 1e9;
        System.out.printf("injected=%d unique=%d dup=%d failed=%d seconds=%.2f rate=%.0f/s%n",
                (long) count * dup, count, dup, fail.get(), sec, count * dup / sec);
        p.shutdown();
    }
}
