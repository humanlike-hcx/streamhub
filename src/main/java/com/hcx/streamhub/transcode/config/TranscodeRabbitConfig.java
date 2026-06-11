package com.hcx.streamhub.transcode.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hcx.streamhub.transcode.mq.TranscodeRabbitNames;

@Configuration
@EnableConfigurationProperties(TranscodeProperties.class)
public class TranscodeRabbitConfig {

	@Bean
	public DirectExchange transcodeExchange() {
		return new DirectExchange(TranscodeRabbitNames.EXCHANGE, true, false);
	}

	@Bean
	public Queue transcodeQueue() {
		return new Queue(TranscodeRabbitNames.QUEUE, true);
	}

	@Bean
	public Binding transcodeBinding(Queue transcodeQueue, DirectExchange transcodeExchange) {
		return BindingBuilder.bind(transcodeQueue)
				.to(transcodeExchange)
				.with(TranscodeRabbitNames.ROUTING_KEY);
	}

	@Bean
	public MessageConverter messageConverter() {
		return new Jackson2JsonMessageConverter();
	}

	@Bean
	public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
		RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
		rabbitTemplate.setMessageConverter(messageConverter);
		return rabbitTemplate;
	}

	@Bean
	public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory,
			MessageConverter messageConverter) {
		SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
		factory.setConnectionFactory(connectionFactory);
		factory.setMessageConverter(messageConverter);
		return factory;
	}
}
