/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.serverless.arklet.core.ops;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

import com.alipay.sofa.ark.api.ArkClient;
import com.alipay.sofa.ark.api.ClientResponse;
import com.alipay.sofa.ark.api.ResponseCode;
import com.alipay.sofa.ark.spi.constant.Constants;
import com.alipay.sofa.ark.spi.model.Biz;
import com.alipay.sofa.ark.spi.model.BizOperation;
import com.alipay.sofa.serverless.arklet.core.command.executor.ExecutorServiceManager;
import com.alipay.sofa.serverless.arklet.core.common.model.CombineInstallRequest;
import com.alipay.sofa.serverless.arklet.core.common.model.CombineInstallResponse;
import com.google.inject.Singleton;

/**
 * @author mingmen
 * @date 2023/6/14
 */
@Singleton
public class UnifiedOperationServiceImpl implements UnifiedOperationService {

    private CombineInstallHelper combineInstallHelper = new CombineInstallHelper();

    @Override
    public void init() {

    }

    @Override
    public void destroy() {

    }

    @Override
    public ClientResponse install(String bizUrl) throws Throwable {
        BizOperation bizOperation = new BizOperation()
                .setOperationType(BizOperation.OperationType.INSTALL);
        bizOperation.putParameter(Constants.CONFIG_BIZ_URL, bizUrl);
        return ArkClient.installOperation(bizOperation);
    }

    public ClientResponse safeInstall(String bizUrl) {
        try {
            return install(bizUrl);
        } catch (Throwable throwable) {
            return new ClientResponse().
                    setCode(ResponseCode.FAILED).
                    setMessage(String.format("internal exception: %s", throwable.getMessage()));
        }
    }

    @Override
    public ClientResponse uninstall(String bizName, String bizVersion) throws Throwable {
        return ArkClient.uninstallBiz(bizName, bizVersion);
    }

    @Override
    public CombineInstallResponse combineInstall(CombineInstallRequest request) throws Throwable {
        List<String> bizUrls = combineInstallHelper.getBizUrlsFromLocalFileSystem(request.getBizDirAbsolutePath());
        ThreadPoolExecutor executorService = ExecutorServiceManager.getArkBizOpsExecutor();
        List<CompletableFuture<ClientResponse>> futures = new ArrayList<>();

        for (String bizUrl : bizUrls) {
            futures.add(CompletableFuture.supplyAsync(() -> safeInstall("file://" + bizUrl), executorService));
        }

        // wait for all install futures done
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();

        // analyze install result per bizUrl
        int counter = 0;
        boolean hasFailed = false;
        Map<String, ClientResponse> bizUrlToInstallResult = new HashMap<>();
        for (CompletableFuture<ClientResponse> future : futures) {
            ClientResponse clientResponse = future.get();
            String bizUrl = bizUrls.get(counter);
            bizUrlToInstallResult.put(bizUrl, clientResponse);
            hasFailed = hasFailed || clientResponse.getCode() != ResponseCode.SUCCESS;
            counter++;
        }

        return CombineInstallResponse.builder().
                code(hasFailed ? ResponseCode.FAILED : ResponseCode.SUCCESS).
                message(hasFailed ? "combine install failed" : "combine install success").
                bizUrlToResponse(bizUrlToInstallResult).
                build();
    }

    @Override
    public List<Biz> queryBizList() {
        return ArkClient.getBizManagerService().getBizInOrder();
    }

    @Override
    public ClientResponse switchBiz(String bizName, String bizVersion) throws Throwable {
        return ArkClient.switchBiz(bizName, bizVersion);
    }
}
