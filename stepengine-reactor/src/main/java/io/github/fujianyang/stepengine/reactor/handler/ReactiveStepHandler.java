package io.github.fujianyang.stepengine.reactor.handler;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface ReactiveStepHandler<C> {

    Mono<Void> forward(C context);
}
