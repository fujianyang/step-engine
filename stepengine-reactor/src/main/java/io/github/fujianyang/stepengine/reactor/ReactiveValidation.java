package io.github.fujianyang.stepengine.reactor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ReactiveValidation {

    private ReactiveValidation() {
    }

    static <C> void validateUniqueStepNames(List<ReactiveExecutionUnit<C>> units) {
        Set<String> names = new LinkedHashSet<>();
        for (ReactiveExecutionUnit<C> unit : units) {
            switch (unit) {
                case ReactiveExecutionUnit.Sequential<C> seq -> {
                    if (!names.add(seq.step().name())) {
                        throw new IllegalArgumentException("duplicate step name: " + seq.step().name());
                    }
                }
                case ReactiveExecutionUnit.Parallel<C> par -> {
                    for (ReactiveStep<C> step : par.group().steps()) {
                        if (!names.add(step.name())) {
                            throw new IllegalArgumentException("duplicate step name: " + step.name());
                        }
                    }
                }
            }
        }
    }
}
