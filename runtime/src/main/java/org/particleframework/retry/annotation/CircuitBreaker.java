/*
 * Copyright 2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.particleframework.retry.annotation;

import org.particleframework.aop.Around;
import org.particleframework.context.annotation.AliasFor;
import org.particleframework.context.annotation.Type;
import org.particleframework.retry.DefaultRetryInterceptor;

import javax.validation.constraints.Digits;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Variation of {@link Retry} that implements the Circuit Breaker pattern. Has higher overhead than
 * {@link Retry} as a {@link org.particleframework.retry.CircuitState} has to be maintained for each method call
 *
 * @author graemerocher
 * @since 1.0
 */
@Documented
@Retention(RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Around
@Type(DefaultRetryInterceptor.class)
public @interface CircuitBreaker {

    /**
     * @return The exception types to include (defaults to all)
     */
    @AliasFor(annotation = Retry.class, member = "includes")
    Class<? extends Throwable>[] includes() default {};

    /**
     * @return The exception types to exclude (defaults to none)
     */
    @AliasFor(annotation = Retry.class, member = "excludes")
    Class<? extends Throwable>[] excludes() default {};

    /**
     * @return The maximum number of retry attempts
     */
    @Digits(integer = 4, fraction = 0)
    @AliasFor(annotation = Retry.class, member = "attempts")
    String attempts() default "3";

    /**
     * @return The delay between retry attempts
     */
    @AliasFor(annotation = Retry.class, member = "delay")
    String delay() default "500ms";

    /**
     * @return The multiplier to use to calculate the delay between retries
     */
    @Digits(integer = 2, fraction = 2)
    @AliasFor(annotation = Retry.class, member = "multiplier")
    String multiplier() default "0";

    /**
     * The maximum overall delay for an operation to complete until the Circuit state is set to {@link org.particleframework.retry.CircuitState#OPEN}
     *
     * @return The maximum overall delay
     */
    @AliasFor(annotation = Retry.class, member = "maxDelay")
    String maxDelay() default "5s";

    /**
     * Sets the {@link java.time.Duration} of time before resetting the circuit to {@link org.particleframework.retry.CircuitState#HALF_OPEN} allowing a single retry
     *
     * @return The {@link java.time.Duration} of time before reset
     */
    String reset() default "20s";
}
