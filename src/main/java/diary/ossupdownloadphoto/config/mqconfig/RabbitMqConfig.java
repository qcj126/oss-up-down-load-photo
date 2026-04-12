package diary.ossupdownloadphoto.config.mqconfig;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RabbitMqConfig {
    // ========== 常量定义 ==========
    public static final String OSS_UPLOAD_EXCHANGE = "oss.upload.exchange";
    public static final String OSS_UPLOAD_QUEUE = "oss.upload.queue";
    public static final String OSS_UPLOAD_ROUTING_KEY = "oss.upload.success";

    // 死信相关
    public static final String DLX_EXCHANGE = "oss.upload.dlx.exchange";
    public static final String DLX_QUEUE = "oss.upload.dlx.queue";
    public static final String DLX_ROUTING_KEY = "oss.upload.dlx";

    @Bean
    public DirectExchange dlxExchange() {
        return ExchangeBuilder.directExchange(DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue dlxQueue() {
        return QueueBuilder.durable(DLX_QUEUE).build();
    }

    @Bean
    public Binding dlxBinding() {
        return BindingBuilder.bind(dlxQueue()).to(dlxExchange()).with(DLX_ROUTING_KEY);
    }

    // ========== 业务交换机与队列（绑定死信） ==========
    @Bean
    public DirectExchange ossUploadExchange() {
        return ExchangeBuilder.directExchange(OSS_UPLOAD_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue ossUploadQueue() {
        return QueueBuilder.durable(OSS_UPLOAD_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)      // 指定死信交换机
                .withArgument("x-dead-letter-routing-key", DLX_ROUTING_KEY) // 死信路由键
                .build();
    }

    @Bean
    public Binding ossUploadBinding() {
        return BindingBuilder.bind(ossUploadQueue())
                .to(ossUploadExchange())
                .with(OSS_UPLOAD_ROUTING_KEY);
    }

    // ========== JSON 消息转换器 ==========
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());

        // 生产者确认回调
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                log.info("消息成功发送到交换机: {}", correlationData.getId());
            } else {
                log.error("消息发送到交换机失败: {}，原因: {}", correlationData.getId(), cause);
                // 可在此添加失败补偿逻辑（如存入数据库待重发）
            }
        });

        // 路由失败回调（消息到达交换机但未匹配到队列）
        template.setReturnsCallback(returned -> {
            log.error("消息路由失败，交换机: {}，路由键: {}，原因: {}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyText());
            // 可在此添加补偿逻辑
        });
        return template;
    }

    // ========== 消费者容器工厂（手动确认） ==========
    @Bean(name = "rabbitListenerContainerFactory")
    public RabbitListenerContainerFactory<?> rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter());
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);  // 手动确认
        factory.setPrefetchCount(10);
        return factory;
    }
}
