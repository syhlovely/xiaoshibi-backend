package com.yupi.springbootinit.message;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

/**
 * @author xiaoshi
 * CreateTime 2023/6/24 16:17
 */
@SpringBootTest
class RabbitMqMessageProducerTest {



    @Resource
    private RabbitMqMessageProducer messageProducer;


    @Test
    void sendMessage() {
        messageProducer.sendMessage("demo_exchange","demo_queue","欢迎来到十二智能BI系统");
    }


    }
