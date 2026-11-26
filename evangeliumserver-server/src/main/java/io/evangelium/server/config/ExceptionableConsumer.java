package io.evangelium.server.config;

@FunctionalInterface
public interface ExceptionableConsumer<T> {

    void accept(T t) throws Exception;

}
