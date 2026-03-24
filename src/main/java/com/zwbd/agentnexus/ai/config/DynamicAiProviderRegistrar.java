package com.zwbd.agentnexus.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.*;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/**
 * @Author: wnli
 * @Date: 2025/10/20 15:07
 * @Desc:
 */
@Deprecated
@Slf4j
//@Configuration
public class DynamicAiProviderRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    private Environment environment;

    // 1. Spring 会自动调用这个方法，注入 Environment
    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        log.info("Starting dynamic AI provider registration...");
        // 手动从 Environment 绑定属性
        Map<String, DynamicAiProvidersProperties.ProviderConfig> providers =
                Binder.get(environment)
                        .bind("spring.ai.dynamic-providers",
                                Bindable.mapOf(String.class, DynamicAiProvidersProperties.ProviderConfig.class))
                        .orElse(Collections.emptyMap()); // 绑定失败时返回一个空 Map

        log.info("Found {} dynamic AI provider(s) to register.", providers.size());
        // 2. 遍历YAML中定义的每一个 "provider"
        providers.forEach((name, config) -> {
            log.info("--- Registering AI provider: [{}] ---", name);

            // --- 1. 注册 OpenAiApi (使用 Supplier, 因为无 Bean 依赖) ---
            Supplier<OpenAiApi> apiSupplier = () -> OpenAiApi.builder()
                    .apiKey(config.getApiKey() != null ? config.getApiKey() : "")
                    .baseUrl(config.getBaseUrl())
                    .build();
            String apiBeanName = name + "Api";
            BeanDefinitionBuilder apiBuilder = BeanDefinitionBuilder.genericBeanDefinition(OpenAiApi.class, apiSupplier);
            registry.registerBeanDefinition(apiBeanName, apiBuilder.getBeanDefinition());
            log.debug("Registered OpenAiApi bean: [{}] (using Supplier)", apiBeanName);

            // 注册 ChatModel 和 ChatClient.Builder
            if (config.getChat() != null && config.getChat().getModel() != null) {

                // --- 2. 注册 OpenAiChatOptions (使用 Supplier, 因为无 Bean 依赖) ---
                Supplier<OpenAiChatOptions> optionsSupplier = () -> OpenAiChatOptions.builder()
                        .model(config.getChat().getModel())
                        .temperature(config.getChat().getOptions() != null ? config.getChat().getOptions().getTemperature() : null)
                        .build();
                String optionsBeanName = name + "ChatOptions";
                BeanDefinitionBuilder optionsBuilder = BeanDefinitionBuilder.genericBeanDefinition(OpenAiChatOptions.class, optionsSupplier);
                registry.registerBeanDefinition(optionsBeanName, optionsBuilder.getBeanDefinition());
                log.debug("Registered OpenAiChatOptions bean: [{}] (using Supplier)", optionsBeanName);

                // --- 3. 注册 OpenAiChatModel (使用 Builder 工厂模式, 因为有 Bean 依赖) ---
                //    这将分两步：(A) 注册配置好的 builder (B) 注册调用 .build() 的 factory

                // (A) 定义配置好的 OpenAiChatModel.Builder Bean
                String chatModelBuilderName = name + "ChatModelBuilderInternal";
                BeanDefinitionBuilder builderBean = BeanDefinitionBuilder.rootBeanDefinition(OpenAiChatModel.class, "builder"); // 静态 `builder()`

                // 【核心】: 注入依赖
                builderBean.addPropertyReference("openAiApi", apiBeanName); // 映射到 .openAiApi(api)
                builderBean.addPropertyReference("defaultOptions", optionsBeanName); // 映射到 .defaultOptions(options)


                registry.registerBeanDefinition(chatModelBuilderName, builderBean.getBeanDefinition());

                // (B) 定义最终的 OpenAiChatModel Bean, 它调用 (A) 的 .build() 方法
                String chatModelBeanName = name + "ChatModel";
                BeanDefinitionBuilder chatModelBean = BeanDefinitionBuilder.rootBeanDefinition(ChatModel.class);
                // 3. 在 BeanDefinition 上设置工厂
                AbstractBeanDefinition chatModelDef = chatModelBean.getBeanDefinition();
                chatModelDef.setFactoryBeanName(chatModelBuilderName); // 工厂 Bean
                chatModelDef.setFactoryMethodName("build"); // 工厂方法

                // 添加 @Qualifier 和 @Primary
                chatModelDef.addQualifier(new AutowireCandidateQualifier(Qualifier.class, chatModelBeanName));
                if (config.getChat().isPrimary()) {
                    chatModelDef.setPrimary(true);
                    log.info("... marking [{}] as @Primary ChatModel.", chatModelBeanName);
                }
                registry.registerBeanDefinition(chatModelBeanName, chatModelDef);
                log.info("Registered ChatModel bean: [{}].", chatModelBeanName);

                // --- 4. 注册 ChatClient.Builder (这个逻辑不变, 它依赖 ChatModel) ---
                String builderBeanName = name + "ChatClientBuilder";
                BeanDefinitionBuilder clientBuilder = BeanDefinitionBuilder.rootBeanDefinition(ChatClient.class, "builder");
                clientBuilder.addConstructorArgReference(chatModelBeanName); // 依赖 `ChatModel`
                clientBuilder.setScope(BeanDefinition.SCOPE_PROTOTYPE);

                AbstractBeanDefinition clientBeanDefinition = clientBuilder.getBeanDefinition();
                clientBeanDefinition.addQualifier(new AutowireCandidateQualifier(Qualifier.class, builderBeanName));
                if (config.getChat().isPrimary()) {
                    clientBeanDefinition.setPrimary(true);
                    log.info("... marking [{}] as @Primary ChatClient.Builder.", builderBeanName);
                }
                registry.registerBeanDefinition(builderBeanName, clientBeanDefinition);
                log.info("Registered ChatClient.Builder (prototype) bean: [{}]", builderBeanName);
            }

            // 注册 EmbeddingModel (完全同理)
            if (config.getEmbedding() != null && config.getEmbedding().getModel() != null) {
                // --- 2. 注册 OpenAiEmbeddingOptions ---
                Supplier<OpenAiEmbeddingOptions> optionsSupplier = () -> OpenAiEmbeddingOptions.builder()
                        .model(config.getEmbedding().getModel())
                        .build();
                String optionsBeanName = name + "EmbeddingOptions";
                BeanDefinitionBuilder optionsBuilder = BeanDefinitionBuilder.genericBeanDefinition(OpenAiEmbeddingOptions.class, optionsSupplier);
                registry.registerBeanDefinition(optionsBeanName, optionsBuilder.getBeanDefinition());
                log.debug("Registered OpenAiEmbeddingOptions bean: [{}] (using Supplier)", optionsBeanName);

                // --- 3. 注册 OpenAiEmbeddingModel (使用 Builder 工厂模式) ---
                // (A) 定义配置好的 OpenAiEmbeddingModel.Builder Bean
                String embeddingModelBuilderName = name + "EmbeddingModelBuilderInternal";
                BeanDefinitionBuilder builderBean = BeanDefinitionBuilder.rootBeanDefinition(OpenAiEmbeddingModel.class, "builder");
                builderBean.addPropertyReference("openAiApi", apiBeanName);
                builderBean.addPropertyReference("defaultOptions", optionsBeanName);
                registry.registerBeanDefinition(embeddingModelBuilderName, builderBean.getBeanDefinition());

                // (B) 定义最终的 OpenAiEmbeddingModel Bean
                String embeddingModelBeanName = name + "EmbeddingModel";
                BeanDefinitionBuilder embeddingModelBean = BeanDefinitionBuilder.rootBeanDefinition(EmbeddingModel.class);
                AbstractBeanDefinition embeddingModelDef = embeddingModelBean.getBeanDefinition();
                embeddingModelDef.setFactoryBeanName(embeddingModelBuilderName);
                embeddingModelDef.setFactoryMethodName("build");

                embeddingModelDef.addQualifier(new AutowireCandidateQualifier(Qualifier.class, embeddingModelBeanName));
                if (config.getEmbedding().isPrimary()) {
                    embeddingModelDef.setPrimary(true);
                    log.info("... marking [{}] as @Primary EmbeddingModel.", embeddingModelBeanName);
                }
                registry.registerBeanDefinition(embeddingModelBeanName, embeddingModelDef);
                log.info("Registered EmbeddingModel bean: [{}].", embeddingModelBeanName);
            }

            log.info("--- Finished registering AI provider: [{}] ---", name);
        });
    }


}
