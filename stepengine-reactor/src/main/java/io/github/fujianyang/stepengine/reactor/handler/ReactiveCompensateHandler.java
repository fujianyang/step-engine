package io.github.fujianyang.stepengine.reactor.handler;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface ReactiveCompensateHandler<C> {

    Mono<Void> compensate(C context);
}
