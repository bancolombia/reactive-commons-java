package org.reactivecommons.async.impl.listeners;

import com.rabbitmq.client.AMQP;
import lombok.extern.java.Log;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.impl.DiscardNotifier;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.Headers;
import org.reactivecommons.async.impl.QueryExecutor;
import org.reactivecommons.async.impl.communications.Message;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import org.reactivecommons.async.impl.converters.MessageConverter;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;

import java.util.HashMap;
import java.util.function.Function;

import static java.lang.Boolean.TRUE;
import static java.util.Optional.ofNullable;
import static org.reactivecommons.async.impl.Headers.*;

@Log
//TODO: Organizar inferencia de tipos de la misma forma que en comandos y eventos
public class ApplicationQueryListener extends GenericMessageListener {


    private final MessageConverter converter;
    private final HandlerResolver handlerResolver;
    private final ReactiveMessageSender sender;
    private final String replyExchange;
    private final String directExchange;
    private final boolean withDLQRetry;
    private final int retryDelay;


    public ApplicationQueryListener(ReactiveMessageListener listener, String queueName, HandlerResolver resolver, ReactiveMessageSender sender, String directExchange, MessageConverter converter, String replyExchange, boolean withDLQRetry, long maxRetries, int retryDelay, DiscardNotifier discardNotifier) {
        super(queueName, listener, withDLQRetry, maxRetries, discardNotifier, "query");
        this.retryDelay = retryDelay;
        this.withDLQRetry = withDLQRetry;
        this.converter = converter;
        this.handlerResolver = resolver;
        this.sender = sender;
        this.replyExchange = replyExchange;
        this.directExchange = directExchange;
    }


    @Override
    protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
        final RegisteredQueryHandler<Object, Object> handler1 = handlerResolver.getQueryHandler(executorPath);
        if (handler1 == null) {
            return message -> Mono.error(new RuntimeException("Handler Not registered for Query: " + executorPath));
        }
        final Class<?> handlerClass = (Class<?>) handler1.getQueryClass();
        Function<Message, Object> messageConverter = msj -> converter.readAsyncQuery(msj, handlerClass).getQueryData();
        final QueryExecutor executor = new QueryExecutor(handler1.getHandler(), messageConverter);
        return executor::execute;
    }

    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        if (withDLQRetry) {
            final Mono<AMQP.Exchange.DeclareOk> declareExchange = creator.declare(ExchangeSpecification.exchange(directExchange).durable(true).type("direct"));
            final Mono<AMQP.Exchange.DeclareOk> declareExchangeDLQ = creator.declare(ExchangeSpecification.exchange(directExchange+".DLQ").durable(true).type("direct"));
            final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declareQueue(queueName, directExchange+".DLQ");
            final Mono<AMQP.Queue.DeclareOk> declareDLQ = creator.declareDLQ(queueName, directExchange, retryDelay);
            final Mono<AMQP.Queue.BindOk> binding = creator.bind(BindingSpecification.binding(directExchange, queueName, queueName));
            final Mono<AMQP.Queue.BindOk> bindingDLQ = creator.bind(BindingSpecification.binding(directExchange+".DLQ", queueName, queueName + ".DLQ"));
            return declareExchange.then(declareExchangeDLQ).then(declareQueue).then(declareDLQ).then(binding).then(bindingDLQ).then();
        } else {
            final Mono<AMQP.Exchange.DeclareOk> declareExchange = creator.declare(ExchangeSpecification.exchange(directExchange).durable(true).type("direct"));
            final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declare(QueueSpecification.queue(queueName).durable(true));
            final Mono<AMQP.Queue.BindOk> binding = creator.bind(BindingSpecification.binding(directExchange, queueName, queueName));
            return declareExchange.then(declareQueue).then(binding).then();
        }
    }

    @Override
    protected String getExecutorPath(AcknowledgableDelivery msj) {
        return msj.getProperties().getHeaders().get(SERVED_QUERY_ID).toString();
    }

    @Override
    protected Function<Mono<Object>, Mono<Object>> enrichPostProcess(Message msg) {
        return m -> m.materialize().flatMap(signal -> {
            if (signal.isOnError()) {
                return Mono.error(ofNullable(signal.getThrowable()).orElseGet(RuntimeException::new));
            }

            final String replyID = msg.getProperties().getHeaders().get(REPLY_ID).toString();
            final String correlationID = msg.getProperties().getHeaders().get(CORRELATION_ID).toString();
            final HashMap<String, Object> headers = new HashMap<>();
            headers.put(CORRELATION_ID, correlationID);

            if (!signal.hasValue()) {
                headers.put(Headers.COMPLETION_ONLY_SIGNAL, TRUE.toString());
            }

            return sender.sendWithConfirm(signal.get(),replyExchange, replyID, headers);
        });
    }
}


