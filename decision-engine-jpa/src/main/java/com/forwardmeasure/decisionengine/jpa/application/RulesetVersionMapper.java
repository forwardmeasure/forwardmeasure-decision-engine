/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.jpa.application;

import com.forwardmeasure.decisionengine.domain.RulesetVersion;
import com.forwardmeasure.decisionengine.jpa.entity.RulesetVersionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper
public interface RulesetVersionMapper {
  RulesetVersionMapper INSTANCE = Mappers.getMapper(RulesetVersionMapper.class);

  @Mapping(target = "version", source = "rulesetVersion")
  RulesetVersion toDomain(RulesetVersionEntity entity);

  @Mapping(target = "rulesetVersion", source = "version")
  RulesetVersionEntity toEntity(RulesetVersion domain);
}
