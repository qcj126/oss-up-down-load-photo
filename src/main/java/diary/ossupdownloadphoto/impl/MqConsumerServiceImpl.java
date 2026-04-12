package diary.ossupdownloadphoto.impl;

import com.rabbitmq.client.Channel;
import diary.ossupdownloadphoto.config.consts.PhotoStatusConst;
import diary.ossupdownloadphoto.config.mqconfig.RabbitMqConfig;
import diary.ossupdownloadphoto.mapper.PhotoMapper;
import diary.ossupdownloadphoto.po.OssUploadSuccessMsg;
import diary.ossupdownloadphoto.service.MqConsumerService;
import jakarta.annotation.Resource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MqConsumerServiceImpl implements MqConsumerService {
    @Resource
    private PhotoMapper photoMapper;
    /*
    * 监听上传成功消息，更新数据库状态
    * */
    @RabbitListener(queues = RabbitMqConfig.OSS_UPLOAD_QUEUE,
            containerFactory = "rabbitListenerContainerFactory")
    @Override
    public void handleUploadSuccess(OssUploadSuccessMsg message, Message amqpMessage, Channel channel) {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        Long id = message.getId();
        String ossUrl = message.getOssUrl();
        try {
            log.info("收到上传成功消息，recordId: {}, ossUrl: {}", id, ossUrl);
            // 更新数据库记录
            Integer count = photoMapper.updatePhotoStatusById(id, ossUrl, PhotoStatusConst.PHOTO_STATUS_SUCCESS);
            if (count > 0) {
                log.info("数据库记录更新成功，recordId: {}", id);
                // 手动确认消息处理成功
                channel.basicAck(deliveryTag, false);
                log.info("消息处理成功并已确认，recordId: {}", id);
            } else {
                log.error("数据库记录更新失败，recordId: {}", id);
                // 拒绝消息，requeue=false 表示不重新入队（交由死信队列处理）
                channel.basicNack(deliveryTag, false, false);
                log.error("处理消息失败，recordId: {}", id);
            }
        } catch (Exception e) {
            log.error("处理消息失败，recordId: {}, 异常: {}", id, e.getMessage());
            try {
                // 拒绝消息，requeue=false 表示不重新入队（交由死信队列处理）
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception ex) {
                log.error("消息拒绝处理功能失败了", ex);
            }
        }
    }

    /**
     * 死信队列消费者（处理最终失败的消息）
     */
    @RabbitListener(queues = RabbitMqConfig.DLX_QUEUE)
    public void handleDeadLetter(OssUploadSuccessMsg message,
                                 Message amqpMessage,
                                 Channel channel) {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        try {
            Long id = message.getId();
            log.error("死信消息，recordId: {}，原始消息已丢失或消费失败超过重试次数",
                    id != null ? id : "未知");

            // 将数据库记录标记为 FAILED
            if (message.getId() != null) {
                String ossUrl = message.getOssUrl();
                log.info("收到更新数据库失败的消息，recordId: {}, ossUrl: {}", id, ossUrl);
                // 更新数据库记录
                Integer count = photoMapper.updatePhotoStatusById(id, ossUrl, PhotoStatusConst.PHOTO_STATUS_FAILED);
                if (count > 0) {
                    log.info("数据库记录更新成功，recordId: {}", id);
                } else {
                    log.error("数据库记录更新失败，recordId: {}", id);
                }
            }
            // 死信消息也需确认
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("处理死信消息异常", e);
        }
    }
}
