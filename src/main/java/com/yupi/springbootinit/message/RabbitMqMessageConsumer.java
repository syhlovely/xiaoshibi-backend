package com.yupi.springbootinit.message;

import com.rabbitmq.client.Channel;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.manager.AiManager;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.service.ChartService;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

import static com.yupi.springbootinit.constant.CommonConstant.AI_ID;

/**
 * @author Shier
 * CreateTime 2023/6/24 15:53
 * RabbitMQ 消费者
 */
@Component
@Slf4j
public class RabbitMqMessageConsumer {

    @Autowired
    private ChartService chartService;

    @Resource
    private AiManager aiManager;

    /**
     * 指定程序监听的消息队列和确认机制
     * @param message
     * @param channel
     * @param deliveryTag
     */
    @SneakyThrows
    @RabbitListener(queues = {"demo_queue"}, ackMode = "MANUAL")
    private void receiveMessage(String message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag){
        if (StringUtils.isBlank(message)){
            //消息为空，拒绝消息
//            handleChartUpdateError(chartId,"更新图表信息失败");
            channel.basicNack(deliveryTag,false,false);
        }
        long chartId = Long.parseLong(message);

        //根据消息中的id查询出图表信息
        Chart chart = chartService.getById(chartId);

        //先将图表信息状态改成执行中
        boolean update = chartService.updateById(Chart.builder().id(chartId).chartStatus("running").build());
        if (!update){
            handleChartUpdateError(chartId,"更新图表信息失败");
            //拒绝消息
            channel.basicNack(deliveryTag,false,false);
            return;
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
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"AI生成错误,请重新生成一次");
        }
        //生成的echarts代码
        String echarts = split[1];
        //生成的图表分析结论
        String info = split[2];
        //更新图表信息，更新状态为生成图表成功，succeed
        boolean succeed = chartService.updateById(Chart.builder().genChart(echarts).genResult(info).chartStatus("succeed").id(chart.getId()).build());
        if (!succeed){
            handleChartUpdateError(chartId,"更新图表信息失败");
            //mq手动拒绝
            channel.basicNack(deliveryTag,false,false);
            return;
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