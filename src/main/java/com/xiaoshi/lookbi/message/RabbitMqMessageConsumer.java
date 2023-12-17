package com.xiaoshi.lookbi.message;

import com.rabbitmq.client.Channel;
import com.xiaoshi.lookbi.common.ErrorCode;
import com.xiaoshi.lookbi.config.RabbitMQConfig;
import com.xiaoshi.lookbi.exception.BusinessException;
import com.xiaoshi.lookbi.manager.AiManager;
import com.xiaoshi.lookbi.model.entity.Chart;
import com.xiaoshi.lookbi.service.ChartService;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

import static com.xiaoshi.lookbi.constant.CommonConstant.AI_ID;

/**
 * @author shiyinghan
 * CreateTime 2023/8/11 15:53
 * RabbitMQ 消费者
 */
@Component
@Slf4j
public class RabbitMqMessageConsumer {

    @Autowired
    private ChartService chartService;

    @Resource
    private AiManager aiManager;


    @RabbitListener(queues = RabbitMQConfig.DEAD_QUEUE)
    public void listener_dead(String msg, Channel channel, Message message) throws IOException {
        System.out.println("=======================================================================================");
        System.out.println("死信接收到消息" + msg);
        System.out.println("唯一标识:" + message.getMessageProperties().getCorrelationId());
        System.out.println("messageID:" + message.getMessageProperties().getMessageId());

//        if (StringUtils.isBlank(msg)){
//            //消息为空，拒绝消息
////            handleChartUpdateError(chartId,"更新图表信息失败");
//            channel.basicNack(deliveryTag,false,false);
//            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"图表为空");
//        }
        //生成失败的消息进入死信队列，这里将生成失败的任务状态改成failed
        Chart chart = Chart.builder().id(Long.valueOf(msg)).chartStatus("failed").build();
        chartService.updateById(chart);

        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }



    /**
     * 指定程序监听的消息队列和确认机制
     * @param message
     * @param channel
     * @param deliveryTag
     */
    @SneakyThrows
    @RabbitListener(queues = RabbitMQConfig.QUEUE, ackMode = "MANUAL")
    private void receiveMessage(String message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag){
        //测试死信队列
//        if (1==1) {
//            channel.basicNack(deliveryTag, false, false);
//            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图表为空");
//        }

        if (StringUtils.isBlank(message)){
            //消息为空，拒绝消息
//            handleChartUpdateError(chartId,"更新图表信息失败");
//            channel.basicNack(deliveryTag,false,false);
            //重试机制
            throw new RuntimeException("消息错误");
        }
//        int a = 10 / 0 ;
        long chartId = Long.parseLong(message);

        //根据消息中的id查询出图表信息
        Chart chart = chartService.getById(chartId);

        //先将图表信息状态改成执行中
        boolean update = chartService.updateById(Chart.builder().id(chartId).chartStatus("running").build());
        if (!update){
//            handleChartUpdateError(chartId,"更新图表信息失败");
            //拒绝消息
//            channel.basicNack(deliveryTag,false,false);
            throw new RuntimeException("图表更新失败");
//            return;
        }
        //预设已经有了,异步处理图表请求
        //通过yupisdk发请求获取返回值
        //构建用户的输入信息
        String userInput = buildUserInput(chart);
        String doChat = aiManager.doChat(AI_ID, userInput);
        log.info("AI生成：{}",doChat);
        //分割返回的字符串获取我们需要的数据
        String[] split = doChat.split("【【【【【");
        if (split.length<3){
            throw new RuntimeException("AI生成错误,重新生成");
        }
        //生成的echarts代码
        String echarts = split[1];
        //生成的图表分析结论
        String info = split[2];
        //更新图表信息，更新状态为生成图表成功，succeed
        boolean succeed = chartService.updateById(Chart.builder().genChart(echarts).genResult(info).chartStatus("succeed").id(chart.getId()).build());
        if (!succeed){
//            handleChartUpdateError(chartId,"更新图表信息失败");
            //mq手动拒绝
//            channel.basicNack(deliveryTag,false,false);
            throw new RuntimeException("图表更新失败");
        }
      log.info("receiveMessage = {}",message);
        try {
            // 手动确认消息
            channel.basicAck(deliveryTag,false);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 图表更新错误
     *
     * @param chartId
     * @param execMessage
     */
    private void handleChartUpdateError(long chartId, String execMessage) {
        Chart updateChartResult = new Chart();
        updateChartResult.setId(chartId);
        updateChartResult.setChartStatus("failed");
        updateChartResult.setExecMessage("图表更新失败！！");
        boolean updateResult = chartService.updateById(updateChartResult);
        if (!updateResult) {
            log.error("更新图表失败状态失败" + chartId + "," + execMessage);
        }
    }

    /**
     * 构建用户的输入信息
     *
     * @param chart
     * @return
     */
    private String buildUserInput(Chart chart) {
        String goal = chart.getGoal();
        String chartType = chart.getChartType();
        String chartData = chart.getChartData();

        // 无需Prompt，直接调用现有模型
        // 构造用户输入
        StringBuilder userInput = new StringBuilder();
        userInput.append("分析需求：").append("\n");
        // 拼接分析目标
        String userGoal = goal;
        if (StringUtils.isNotBlank(chartType)) {
            userGoal += "，请使用" + chartType;
        }
        userInput.append(userGoal).append("\n");
        userInput.append("原始数据：").append("\n");
        userInput.append(chartData).append("\n");
        return userInput.toString();
    }
}