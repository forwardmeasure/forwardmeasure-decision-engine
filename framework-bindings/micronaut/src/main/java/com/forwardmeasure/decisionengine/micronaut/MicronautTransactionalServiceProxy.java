/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.micronaut;

import io.micronaut.transaction.TransactionDefinition;
import io.micronaut.transaction.TransactionOperations;
import io.micronaut.transaction.support.DefaultTransactionDefinition;
import jakarta.transaction.Transactional;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Objects;
import org.hibernate.Session;

/** Applies Jakarta transaction metadata to the portable JPA service implementation. */
@SuppressWarnings("unchecked")
final class MicronautTransactionalServiceProxy {
  private MicronautTransactionalServiceProxy() {}

  static <S> S create(Class<S> serviceType, S target, TransactionOperations<Session> transactions) {
    Objects.requireNonNull(serviceType, "serviceType");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(transactions, "transactions");
    Object proxy =
        Proxy.newProxyInstance(
            serviceType.getClassLoader(),
            new Class<?>[] {serviceType},
            (instance, method, arguments) ->
                method.getDeclaringClass() == Object.class
                    ? objectMethod(instance, serviceType, method, arguments)
                    : invoke(target, method, arguments, transactions));
    return serviceType.cast(proxy);
  }

  private static Object objectMethod(Object proxy, Class<?> type, Method method, Object[] args) {
    return switch (method.getName()) {
      case "equals" -> proxy == args[0];
      case "hashCode" -> System.identityHashCode(proxy);
      case "toString" -> "MicronautTransactionalServiceProxy[" + type.getName() + "]";
      default -> throw new IllegalStateException("Unsupported Object method: " + method);
    };
  }

  private static Object invoke(
      Object target, Method interfaceMethod, Object[] args, TransactionOperations<Session> tx)
      throws Throwable {
    Method method =
        target.getClass().getMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes());
    Transactional annotation = method.getAnnotation(Transactional.class);
    if (annotation == null) return invokeTarget(target, method, args);
    DefaultTransactionDefinition definition =
        new DefaultTransactionDefinition(
            TransactionDefinition.Propagation.valueOf(annotation.value().name()));
    definition.setRollbackOn(Arrays.asList(annotation.rollbackOn()));
    definition.setDontRollbackOn(Arrays.asList(annotation.dontRollbackOn()));
    return tx.execute(definition, status -> invokeTarget(target, method, args));
  }

  private static Object invokeTarget(Object target, Method method, Object[] args) throws Exception {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof Error error) throw error;
      if (cause instanceof Exception exception) throw exception;
      throw new IllegalStateException(cause);
    }
  }
}
