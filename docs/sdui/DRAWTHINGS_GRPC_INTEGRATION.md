# DrawThings gRPC 完整集成实施文档

> 版本：1.0 | 日期：2026-06-02 | 作者：AgentNexus 团队

---

## 目录

1. [架构概览](#1-架构概览)
2. [Proto 协议分析](#2-proto-协议分析)
3. [环境准备](#3-环境准备)
4. [Maven 工程配置](#4-maven-工程配置)
5. [Java gRPC 客户端实现](#5-java-grpc-客户端实现)
6. [服务层实现](#6-服务层实现)
7. [REST API 增强](#7-rest-api-增强)
8. [AI Agent Tool 增强](#8-ai-agent-tool-增强)
9. [SDUI 工作流节点增强](#9-sdui-工作流节点增强)
10. [配置文件](#10-配置文件)
11. [实施路线图](#11-实施路线图)
12. [测试验证](#12-测试验证)
13. [故障排查](#13-故障排查)

---

## 1. 架构概览

### 整体架构

```
┌──────────────────────────────────────────────────────────────┐
│                     AgentNexus (Java/Spring Boot)             │
│                                                               │
│  ┌──────────┐  ┌────────────┐  ┌──────────────┐             │
│  │ AI Agent │  │ Workflow   │  │ REST API     │             │
│  │ (Chat)   │  │ Node       │  │ Controller   │             │
│  └────┬─────┘  └──────┬─────┘  └──────┬───────┘             │
│       │               │               │                      │
│       └───────────────┼───────────────┘                      │
│                       │                                      │
│              ┌────────▼──────────┐                           │
│              │ ImageGenGrpcService│  ← 新建核心 gRPC 服务    │
│              │ - generate()      │                           │
│              │ - listModels()    │                           │
│              │ - checkFiles()   │                           │
│              │ - progressStream()│                           │
│              └────────┬──────────┘                           │
│                       │                                      │
│              ┌────────▼──────────┐                           │
│              │ GrpcClientConfig  │  ← gRPC Channel 管理      │
│              │ (ManagedChannel)  │                           │
│              └────────┬──────────┘                           │
└───────────────────────┼──────────────────────────────────────┘
                        │ gRPC (protobuf + HTTP/2)
                        │ Port: 7859 (默认)
               ┌────────▼──────────┐
               │  gRPCServerCLI    │  ← DrawThings 无头服务
               │  或 DrawThings    │
               │   (gRPC 模式)     │
               └───────────────────┘
```

### 双协议共存策略

```
DrawThings 协议模式:
┌─────────────────────────────────────────────────────────┐
│  DrawThings GUI App                                      │
│  ┌─────────────────┐  ┌─────────────────┐               │
│  │ HTTP API :7860  │  │ gRPC API :7859  │               │
│  │ (A1111 兼容)    │  │ (完整功能)      │               │
│  │ ✅ txt2img      │  │ ✅ 流式生成      │               │
│  │ ✅ img2img      │  │ ✅ 进度实时推送   │               │
│  │ ❌ 模型管理     │  │ ✅ 文件/模型管理  │               │
│  └────────┬────────┘  └────────┬────────┘               │
└───────────┼────────────────────┼────────────────────────┘
            │                    │
     ┌──────▼──────┐    ┌───────▼────────┐
     │ImageGenService│   │ImageGenGrpcSvc │
     │ (HTTP 客户端) │   │ (gRPC 客户端)  │
     └──────────────┘    └────────────────┘
```

**关键决策**：两套服务共存，通过 `drawthings.protocol` 配置切换或同时启用。

---

## 2. Proto 协议分析

### 2.1 服务定义

```protobuf
// 来源: Libraries/GRPC/Models/Sources/imageService/imageService.proto
// 仓库: github.com/drawthingsai/draw-things-community

service ImageGenerationService {
  // ★ 核心: 文生图/图生图, 服务器流式推送进度+结果
  rpc GenerateImage(ImageGenerationRequest) returns (stream ImageGenerationResponse);

  // 文件管理: 批量检查文件存在于否 (含模型文件)
  rpc FilesExist(FileListRequest) returns (FileExistenceResponse);

  // 文件上传: 客户端流式上传文件到服务器
  rpc UploadFile(stream FileUploadRequest) returns (stream UploadResponse);

  // 服务发现: 获取服务器信息、文件列表、模型元数据
  rpc Echo(EchoRequest) returns (EchoReply);

  // 公钥: 获取服务器公钥
  rpc Pubkey(PubkeyRequest) returns (PubkeyResponse);

  // 配额: 获取算力配额阈值
  rpc Hours(HoursRequest) returns (HoursResponse);
}
```

### 2.2 请求 / 响应关键字段

#### ImageGenerationRequest

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `prompt` | string | 否 | 正向提示词 |
| `negativePrompt` | string | 否 | 负向提示词 |
| `image` | bytes | 否 | 输入图片 sha256（图生图） |
| `mask` | bytes | 否 | 蒙版 sha256（局部重绘） |
| `configuration` | bytes | **关键** | FlatBuffer 编码的生成配置 |
| `override` | MetadataOverride | 否 | 模型/LoRA/ControlNet 覆盖 |
| `hints` | HintProto[] | 否 | ControlNet 提示 |
| `contents` | bytes[] | 否 | 图片字节数据（内容寻址） |
| `chunked` | bool | 否 | 是否接受分块响应 |
| `sharedSecret` | string | 否 | 认证密钥 |

#### ImageGenerationResponse (流式)

| 字段 | 类型 | 说明 |
|------|------|------|
| `generatedImages` | bytes[] | 生成的图片数据 |
| `currentSignpost` | ImageGenerationSignpostProto | 当前进度标记 |
| `signposts` | ImageGenerationSignpostProto[] | 所有进度标记 |
| `previewImage` | bytes | 中间预览图 |
| `chunkState` | enum | `LAST_CHUNK`(0) / `MORE_CHUNKS`(1) |
| `remoteDownload` | RemoteDownloadResponse | 远程下载进度 |
| `tags` | string[] | 服务器追踪标签 |

#### ImageGenerationSignpostProto (进度推送)

```protobuf
message ImageGenerationSignpostProto {
  oneof signpost {
    TextEncoded textEncoded = 1;              // 文本编码完成
    ImageEncoded imageEncoded = 2;            // 图片编码完成
    Sampling sampling = 3;                    // 采样中 (含 step 字段!)
    ImageDecoded imageDecoded = 4;            // 图片解码完成
    SecondPassImageEncoded secondPassImageEncoded = 5;  // 二阶段编码
    SecondPassSampling secondPassSampling = 6;          // 二阶段采样 (含 step)
    SecondPassImageDecoded secondPassImageDecoded = 7;  // 二阶段解码
    FaceRestored faceRestored = 8;            // 面部修复完成
    ImageUpscaled imageUpscaled = 9;          // 放大完成
  }
}

message Sampling {
  int32 step = 1;  // ★ 当前采样步数 —— 这就是进度数据!
}
```

### 2.3 ⚠️ FlatBuffer Configuration 挑战

`ImageGenerationRequest.configuration` 使用 **FlatBuffer** 编码（非 protobuf）。FlatBuffer 是一种零拷贝序列化格式，其 Schema 定义在 `.fbs` 文件中。

**当前状况**：DrawThings 仓库中未公开 `.fbs` 文件。configuration 由 Swift 端 `MediaGenerationPipeline` 生成。

**解决方案（按优先级）**：

#### 方案 A：空配置 + GUI 预设（推荐起步方案）
```java
// 不传 configuration，让服务端使用 DrawThings GUI 当前设置
ImageGenerationRequest request = ImageGenerationRequest.newBuilder()
    .setPrompt("a beautiful sunset")
    .setNegativePrompt("low quality")
    // 不设置 configuration —— 服务端使用当前 GUI 设置
    .build();
```
- ✅ 实现简单
- ✅ 用户在 GUI 中调好参数（模型/采样器/步数等），gRPC 调用时直接使用
- ⚠️ 无法通过 API 动态修改参数

#### 方案 B：HTTP API 桥接配置
```java
// 1. 通过 HTTP API GET / 获取当前配置 (JSON)
// 2. 提取关键参数填入 proto 的 MetadataOverride
// 3. 将配置 JSON 作为字节发送 (需验证服务端是否接受)
```
- ✅ 可以读取当前参数
- ⚠️ 依赖 HTTP API 仍可用

#### 方案 C：逆向 FlatBuffer（长期方案）
- 从 Swift 源码中的 `MediaGenerationPipeline` 推导 FlatBuffer schema
- 在 Java 侧用 Google FlatBuffers 库生成对应的序列化代码
- ⚠️ 维护成本高，DrawThings 版本更新可能导致 schema 变化

**本文档按方案 A + B 混合实现。**

---

## 3. 环境准备

### 3.1 启动 DrawThings gRPC 服务器

#### 选项 1：DrawThings GUI App 切换协议

1. 打开 DrawThings 应用
2. 进入 **Preferences → Advanced**
3. **API Server**: 启用
4. **Protocol**: 选择 **gRPC**（而非 HTTP）
5. **Port**: `7859`（默认 gRPC 端口）
6. **Transport Layer Security**: 开发阶段可关闭
7. **Model Browser**: 启用（用于模型列表功能）
8. ⚠️ **Response Compression**: 关闭（外部客户端不兼容 FPY 压缩）

#### 选项 2：gRPCServerCLI 独立运行（无头模式）

```bash
# 下载地址 (官方 Releases 页面)
# https://github.com/drawthingsai/draw-things-community/releases

# 启动命令
gRPCServerCLI-macOS /path/to/models \
  --port 7859 \
  --no-response-compression \
  --model-browser \
  --no-tls

# 常见模型路径:
# ~/Library/Containers/DrawThings/Data/Documents/Models
# 或 DrawThings 设置中查看 "Model Directory"
```

#### 选项 3：通过 npm dt-skill 管理

```bash
npm install -g @mijuu/drawthings
dt-skill server start      # 启动
dt-skill server status     # 检查状态
dt-skill models            # 列出模型
```

### 3.2 验证 gRPC 服务器

```bash
# 使用 grpcurl 测试 (需要安装: brew install grpcurl)
grpcurl -plaintext localhost:7859 list
# 预期输出: ImageGenerationService

grpcurl -plaintext -d '{"name":"test"}' localhost:7859 \
  ImageGenerationService/Echo
# 预期输出: 服务器信息和文件列表
```

---

## 4. Maven 工程配置

### 4.1 新增依赖

在 `pom.xml` 中添加：

```xml
<properties>
    <grpc.version>1.68.0</grpc.version>
    <protobuf.version>3.25.5</protobuf.version>
    <protoc.version>3.25.5</protoc.version>
    <grpc-spring.version>3.1.0.RELEASE</grpc-spring.version>
</properties>

<dependencies>
    <!-- gRPC 核心 -->
    <dependency>
        <groupId>io.grpc</groupId>
        <artifactId>grpc-netty-shaded</artifactId>
        <version>${grpc.version}</version>
    </dependency>
    <dependency>
        <groupId>io.grpc</groupId>
        <artifactId>grpc-protobuf</artifactId>
        <version>${grpc.version}</version>
    </dependency>
    <dependency>
        <groupId>io.grpc</groupId>
        <artifactId>grpc-stub</artifactId>
        <version>${grpc.version}</version>
    </dependency>

    <!-- Java 注解 (protoc 生成的代码需要) -->
    <dependency>
        <groupId>javax.annotation</groupId>
        <artifactId>javax.annotation-api</artifactId>
        <version>1.3.2</version>
    </dependency>

    <!-- Spring Boot gRPC 客户端 starter -->
    <dependency>
        <groupId>net.devh</groupId>
        <artifactId>grpc-client-spring-boot-starter</artifactId>
        <version>${grpc-spring.version}</version>
    </dependency>

    <!-- Google FlatBuffers (方案 B/C 需要) -->
    <dependency>
        <groupId>com.google.flatbuffers</groupId>
        <artifactId>flatbuffers-java</artifactId>
        <version>24.3.25</version>
    </dependency>
</dependencies>
```

### 4.2 Protobuf Maven 插件

```xml
<build>
    <extensions>
        <extension>
            <groupId>kr.motd.maven</groupId>
            <artifactId>os-maven-plugin</artifactId>
            <version>1.7.1</version>
        </extension>
    </extensions>
    <plugins>
        <plugin>
            <groupId>org.xolstice.maven.plugins</groupId>
            <artifactId>protobuf-maven-plugin</artifactId>
            <version>0.6.1</version>
            <configuration>
                <protocArtifact>
                    com.google.protobuf:protoc:${protoc.version}:exe:${os.detected.classifier}
                </protocArtifact>
                <pluginId>grpc-java</pluginId>
                <pluginArtifact>
                    io.grpc:protoc-gen-grpc-java:${grpc.version}:exe:${os.detected.classifier}
                </pluginArtifact>
                <protoSourceRoot>
                    ${project.basedir}/src/main/proto
                </protoSourceRoot>
            </configuration>
            <executions>
                <execution>
                    <goals>
                        <goal>compile</goal>
                        <goal>compile-custom</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### 4.3 Proto 文件放置

```
src/main/proto/
└── imageService.proto   ← 从 GitHub 获取的完整 proto 文件
```

编译后将自动生成到 `target/generated-sources/protobuf/`:
- `ImageServiceProto.java`
- `ImageGenerationServiceGrpc.java`

---

## 5. Java gRPC 客户端实现

### 5.1 包结构

```
sdui/image/grpc/
├── GrpcClientConfig.java          # gRPC Channel 配置
├── DrawThingsGrpcClient.java      # 封装所有 gRPC 调用
├── GrpcProgressStream.java        # 进度流式响应处理器
└── dto/
    ├── GenerationProgress.java    # 进度 DTO
    ├── ModelInfo.java             # 模型信息 DTO
    └── GrpcGenerationResult.java  # gRPC 生成结果 DTO
```

### 5.2 GrpcClientConfig

```java
package com.zwbd.agentnexus.sdui.image.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NegotiationType;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "drawthings.grpc", name = "enabled", havingValue = "true")
public class GrpcClientConfig {

    @Value("${drawthings.grpc.host:127.0.0.1}")
    private String host;

    @Value("${drawthings.grpc.port:7859}")
    private int port;

    @Value("${drawthings.grpc.use-tls:false}")
    private boolean useTls;

    @Value("${drawthings.grpc.shared-secret:}")
    private String sharedSecret;

    @Value("${drawthings.grpc.max-message-size:1073741824}") // 1GB default
    private int maxMessageSize;

    private ManagedChannel channel;

    @PostConstruct
    public void init() {
        ManagedChannelBuilder<?> builder;
        if (useTls) {
            builder = ManagedChannelBuilder.forAddress(host, port);
        } else {
            builder = NettyChannelBuilder.forAddress(host, port)
                    .negotiationType(NegotiationType.PLAINTEXT);
        }

        channel = builder
                .maxInboundMessageSize(maxMessageSize)
                .maxInboundMetadataSize(maxMessageSize)
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build();

        log.info("gRPC channel created: {}:{}, tls={}", host, port, useTls);
    }

    @PreDestroy
    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Bean
    public ManagedChannel grpcChannel() {
        return channel;
    }

    @Bean
    public ImageGenerationServiceGrpc.ImageGenerationServiceStub asyncStub(
            ManagedChannel channel) {
        return ImageGenerationServiceGrpc.newStub(channel);
    }

    @Bean
    public ImageGenerationServiceGrpc.ImageGenerationServiceBlockingStub blockingStub(
            ManagedChannel channel) {
        return ImageGenerationServiceGrpc.newBlockingStub(channel);
    }

    public String getSharedSecret() {
        return sharedSecret;
    }
}
```

### 5.3 DrawThingsGrpcClient

```java
package com.zwbd.agentnexus.sdui.image.grpc;

import com.google.protobuf.ByteString;
import com.zwbd.agentnexus.sdui.image.grpc.dto.*;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class DrawThingsGrpcClient {

    private final ImageGenerationServiceGrpc.ImageGenerationServiceStub asyncStub;
    private final ImageGenerationServiceGrpc.ImageGenerationServiceBlockingStub blockingStub;
    private final GrpcClientConfig config;

    // 活跃任务追踪: taskId → StreamObserver (用于取消)
    private final ConcurrentHashMap<String, StreamObserver<?>> activeTasks = new ConcurrentHashMap<>();

    /**
     * Echo —— 获取服务器信息、文件列表、模型信息
     */
    public EchoReply echo() {
        EchoRequest request = EchoRequest.newBuilder()
                .setName("AgentNexus")
                .build();
        return blockingStub.echo(request);
    }

    /**
     * 列出服务器上的模型文件
     * Echo 响应中的 files 字段包含所有可用文件
     */
    public List<ModelInfo> listModels() {
        EchoReply reply = echo();
        List<ModelInfo> models = new ArrayList<>();
        if (reply.getFilesList() != null) {
            for (String file : reply.getFilesList()) {
                if (file.endsWith(".ckpt") || file.endsWith(".safetensors")) {
                    models.add(new ModelInfo(file, "unknown", 0));
                }
            }
        }
        return models;
    }

    /**
     * 检查文件是否存在
     */
    public FileExistenceResponse checkFiles(List<String> filePaths) {
        FileListRequest request = FileListRequest.newBuilder()
                .addAllFiles(filePaths)
                .build();
        return blockingStub.filesExist(request);
    }

    /**
     * 流式生成图片 —— 实时进度回调
     *
     * @param prompt          提示词
     * @param negativePrompt  负向提示词
     * @param progressConsumer 进度回调 (step, totalSteps, signpostType)
     * @return CompletableFuture<GrpcGenerationResult>
     */
    public CompletableFuture<GrpcGenerationResult> generateAsync(
            String prompt,
            String negativePrompt,
            Consumer<GenerationProgress> progressConsumer) {

        CompletableFuture<GrpcGenerationResult> future = new CompletableFuture<>();

        ImageGenerationRequest request = buildRequest(prompt, negativePrompt);

        // 用于累积流式响应
        List<byte[]> generatedImages = new ArrayList<>();
        List<GenerationProgress> progressList = new ArrayList<>();

        asyncStub.generateImage(request, new StreamObserver<ImageGenerationResponse>() {
            @Override
            public void onNext(ImageGenerationResponse response) {
                // 处理预览图
                if (response.hasPreviewImage()) {
                    // 预览图在中间步骤中推送
                }

                // 处理进度标记
                if (response.hasCurrentSignpost()) {
                    GenerationProgress progress = parseSignpost(response.getCurrentSignpost());
                    if (progress != null) {
                        progressList.add(progress);
                        if (progressConsumer != null) {
                            progressConsumer.accept(progress);
                        }
                    }
                }

                // 收集所有进度
                for (ImageGenerationSignpostProto sp : response.getSignpostsList()) {
                    GenerationProgress p = parseSignpost(sp);
                    if (p != null) progressList.add(p);
                }

                // 收集生成的图片
                for (ByteString img : response.getGeneratedImagesList()) {
                    generatedImages.add(img.toByteArray());
                }

                // 检查是否为最后一块
                if (response.getChunkState() == ChunkState.LAST_CHUNK) {
                    // 流结束, 完成 Future
                }
            }

            @Override
            public void onError(Throwable t) {
                log.error("gRPC generation error", t);
                future.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                GrpcGenerationResult result = new GrpcGenerationResult(
                        generatedImages, progressList, "completed");
                future.complete(result);
            }
        });

        return future;
    }

    /**
     * 同步生成 —— 收集所有流式响应后返回（阻塞等待）
     */
    public GrpcGenerationResult generateSync(String prompt, String negativePrompt,
                                              Consumer<GenerationProgress> progressConsumer)
            throws Exception {
        return generateAsync(prompt, negativePrompt, progressConsumer).get();
    }

    // ── 私有方法 ──

    private ImageGenerationRequest buildRequest(String prompt, String negativePrompt) {
        ImageGenerationRequest.Builder builder = ImageGenerationRequest.newBuilder()
                .setPrompt(prompt != null ? prompt : "")
                .setNegativePrompt(negativePrompt != null ? negativePrompt : "")
                .setChunked(true);  // 接受分块响应(大图片)

        // 认证
        if (config.getSharedSecret() != null && !config.getSharedSecret().isEmpty()) {
            builder.setSharedSecret(config.getSharedSecret());
        }

        return builder.build();
    }

    /**
     * 解析进度标记 → GenerationProgress
     */
    private GenerationProgress parseSignpost(ImageGenerationSignpostProto sp) {
        switch (sp.getSignpostCase()) {
            case TEXTENCODED:
                return new GenerationProgress("text_encoded", 0, 0);
            case IMAGEENCODED:
                return new GenerationProgress("image_encoded", 0, 0);
            case SAMPLING:
                return new GenerationProgress("sampling",
                        sp.getSampling().getStep(), -1);
            case IMAGEDECODED:
                return new GenerationProgress("image_decoded", 0, 0);
            case SECONDPASSSAMPLING:
                return new GenerationProgress("second_pass_sampling",
                        sp.getSecondPassSampling().getStep(), -1);
            case FACERESTORED:
                return new GenerationProgress("face_restored", 0, 0);
            case IMAGEUPSCALED:
                return new GenerationProgress("image_upscaled", 0, 0);
            default:
                return null;
        }
    }
}
```

### 5.4 DTO 类

#### GenerationProgress

```java
package com.zwbd.agentnexus.sdui.image.grpc.dto;

/**
 * 生成进度 DTO —— 从 gRPC signpost 解析
 */
public record GenerationProgress(
        String type,     // text_encoded | sampling | image_decoded | face_restored | ...
        int step,        // 当前步数 (sampling signpost 时有值)
        int totalSteps   // 总步数 (服务端发送时填充, -1 表示未知)
) {
    /**
     * 计算百分比进度 (估算)
     * @param estimatedTotalSteps 估算总步数
     */
    public int progressPercent(int estimatedTotalSteps) {
        return switch (type) {
            case "text_encoded" -> 5;
            case "image_encoded" -> 10;
            case "sampling" -> {
                int total = totalSteps > 0 ? totalSteps : estimatedTotalSteps;
                if (total <= 0) yield 50;
                yield 10 + (int) ((step / (double) total) * 80);
            }
            case "image_decoded" -> 95;
            case "face_restored" -> 98;
            case "image_upscaled" -> 99;
            default -> 50;
        };
    }
}
```

#### ModelInfo

```java
package com.zwbd.agentnexus.sdui.image.grpc.dto;

public record ModelInfo(
        String filename,
        String type,       // ckpt, safetensors, lora, controlnet, upscaler
        long sizeBytes
) {}
```

#### GrpcGenerationResult

```java
package com.zwbd.agentnexus.sdui.image.grpc.dto;

import java.util.List;

public record GrpcGenerationResult(
        List<byte[]> generatedImages,
        List<GenerationProgress> progressHistory,
        String status
) {
    public boolean isSuccess() { return "completed".equals(status); }
}
```

---

## 6. 服务层实现

### 6.1 ImageGenGrpcService

```java
package com.zwbd.agentnexus.sdui.image;

import com.zwbd.agentnexus.file.FileStorageService;
import com.zwbd.agentnexus.sdui.image.grpc.DrawThingsGrpcClient;
import com.zwbd.agentnexus.sdui.image.grpc.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "drawthings.grpc", name = "enabled", havingValue = "true")
public class ImageGenGrpcService {

    private final DrawThingsGrpcClient grpcClient;
    private final FileStorageService fileStorageService;

    // 活跃任务的状态缓存
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();

    /**
     * 异步生成图片 —— 返回 taskId 供轮询
     */
    public String submitGeneration(String prompt, String negativePrompt) {
        String taskId = UUID.randomUUID().toString();
        TaskState state = new TaskState(taskId, "QUEUED");
        tasks.put(taskId, state);

        Consumer<GenerationProgress> progressCallback = progress -> {
            state.setCurrentProgress(progress);
            if (state.getTotalSteps() <= 0 && progress.step() > 0) {
                // 从 signpost 估算总步数
            }
        };

        state.setStatus("RUNNING");
        grpcClient.generateAsync(prompt, negativePrompt, progressCallback)
                .thenAccept(result -> {
                    // 保存图片到本地
                    List<String> urls = new ArrayList<>();
                    for (byte[] imgBytes : result.generatedImages()) {
                        String filename = fileStorageService.storeBytes(imgBytes, ".png");
                        urls.add("/api/v1/sdui/image/view/" + filename);
                    }
                    state.setStatus("COMPLETED");
                    state.setImageUrls(urls);
                    state.setProgressHistory(result.progressHistory());
                })
                .exceptionally(ex -> {
                    log.error("gRPC generation failed: taskId={}", taskId, ex);
                    state.setStatus("FAILED");
                    state.setError(ex.getMessage());
                    return null;
                });

        return taskId;
    }

    /**
     * 查询任务状态
     */
    public TaskState getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 列出活跃任务
     */
    public List<TaskState> listTasks() {
        return new ArrayList<>(tasks.values());
    }

    /**
     * 取消任务 (需要 gRPC 取消支持)
     */
    public boolean cancelTask(String taskId) {
        TaskState state = tasks.get(taskId);
        if (state == null || !"RUNNING".equals(state.getStatus())) {
            return false;
        }
        // TODO: 调用 gRPC cancellation
        state.setStatus("CANCELLED");
        return true;
    }

    /**
     * 获取服务器信息
     */
    public Map<String, Object> getServerInfo() {
        EchoReply echo = grpcClient.echo();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("message", echo.getMessage());
        info.put("files", echo.getFilesList());
        info.put("serverIdentifier", echo.getServerIdentifier());
        info.put("sharedSecretRequired", echo.getSharedSecretMissing());
        return info;
    }

    /**
     * 列出模型
     */
    public List<ModelInfo> listModels() {
        return grpcClient.listModels();
    }

    /**
     * 健康检查
     */
    public boolean isAvailable() {
        try {
            grpcClient.echo();
            return true;
        } catch (Exception e) {
            log.debug("gRPC health check failed: {}", e.getMessage());
            return false;
        }
    }
}
```

### 6.2 TaskState

```java
package com.zwbd.agentnexus.sdui.image;

import com.zwbd.agentnexus.sdui.image.grpc.dto.GenerationProgress;
import lombok.Data;
import java.time.Instant;
import java.util.*;

@Data
public class TaskState {
    private final String taskId;
    private volatile String status;  // QUEUED | RUNNING | COMPLETED | FAILED | CANCELLED
    private final Instant createdAt = Instant.now();
    private List<String> imageUrls;
    private GenerationProgress currentProgress;
    private List<GenerationProgress> progressHistory;
    private String error;
    private int totalSteps = -1;

    public TaskState(String taskId, String status) {
        this.taskId = taskId;
        this.status = status;
    }

    public int getProgressPercent() {
        if (currentProgress == null) return 0;
        return currentProgress.progressPercent(totalSteps);
    }
}
```

---

## 7. REST API 增强

### 7.1 ImageGenGrpcController

```java
package com.zwbd.agentnexus.sdui.image;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.image.grpc.dto.ModelInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/sdui/image/grpc")
@RequiredArgsConstructor
@Tag(name = "Image Generation (gRPC)", description = "DrawThings gRPC 接口 —— 支持流式进度推送、模型管理")
@ConditionalOnProperty(prefix = "drawthings.grpc", name = "enabled", havingValue = "true")
public class ImageGenGrpcController {

    private final ImageGenGrpcService grpcService;

    @PostMapping("/generate/async")
    @Operation(summary = "异步文生图 (gRPC)",
            description = "提交生成任务，立即返回 taskId，通过 GET /task/{taskId} 轮询进度和结果")
    public ApiResponse<Map<String, String>> submitAsync(
            @RequestParam String prompt,
            @RequestParam(required = false, defaultValue = "") String negativePrompt) {
        String taskId = grpcService.submitGeneration(prompt, negativePrompt);
        return ApiResponse.success(Map.of("taskId", taskId, "status", "QUEUED"));
    }

    @GetMapping("/task/{taskId}")
    @Operation(summary = "查询任务状态",
            description = "返回任务状态、进度百分比、完成后的图片 URLs、完整的进度历史")
    public ApiResponse<TaskState> getTask(@PathVariable String taskId) {
        TaskState state = grpcService.getTask(taskId);
        if (state == null) {
            return ApiResponse.error(40400, "Task not found: " + taskId);
        }
        return ApiResponse.success(state);
    }

    @GetMapping("/tasks")
    @Operation(summary = "列出所有活跃任务")
    public ApiResponse<List<TaskState>> listTasks() {
        return ApiResponse.success(grpcService.listTasks());
    }

    @DeleteMapping("/task/{taskId}")
    @Operation(summary = "取消任务")
    public ApiResponse<Map<String, Object>> cancelTask(@PathVariable String taskId) {
        boolean cancelled = grpcService.cancelTask(taskId);
        return ApiResponse.success(Map.of("taskId", taskId, "cancelled", cancelled));
    }

    @GetMapping("/server")
    @Operation(summary = "获取 gRPC 服务器信息")
    public ApiResponse<Map<String, Object>> serverInfo() {
        return ApiResponse.success(grpcService.getServerInfo());
    }

    @GetMapping("/models")
    @Operation(summary = "列出可用模型 (gRPC)")
    public ApiResponse<List<ModelInfo>> listModels() {
        return ApiResponse.success(grpcService.listModels());
    }

    @GetMapping("/health")
    @Operation(summary = "gRPC 连接健康检查")
    public ApiResponse<Map<String, Object>> health() {
        boolean available = grpcService.isAvailable();
        return ApiResponse.success(Map.of(
                "available", available,
                "protocol", "gRPC",
                "message", available ? "gRPC server is reachable" : "gRPC server not reachable"
        ));
    }
}
```

---

## 8. AI Agent Tool 增强

### 8.1 修改 ImageGenTool

在现有 `ImageGenTool` 中增加 gRPC 方法：

```java
@Tool(description = """
        Generate an image with real-time progress tracking via local DrawThings gRPC.
        Returns a taskId that can be used to check generation progress.
        Use this for long-running generations where you need progress updates.""")
public String generateImageWithProgress(
        @ToolParam(description = "Image generation prompt") String prompt,
        @ToolParam(description = "Negative prompt") String negativePrompt
) {
    if (!grpcService.isAvailable()) {
        return "❌ DrawThings gRPC 服务不可用。请确保已启动 gRPCServerCLI 或切换至 gRPC 协议。";
    }
    String taskId = grpcService.submitGeneration(prompt,
            negativePrompt != null ? negativePrompt : "");
    return "✅ 任务已提交！\n" +
           "Task ID: " + taskId + "\n" +
           "查询进度: GET /api/v1/sdui/image/grpc/task/" + taskId + "\n" +
           "提示词: " + prompt;
}

@Tool(description = "Check the progress of a running image generation task by taskId")
public String checkProgress(
        @ToolParam(description = "The task ID returned by generateImageWithProgress") String taskId
) {
    TaskState state = grpcService.getTask(taskId);
    if (state == null) return "❌ 任务不存在: " + taskId;
    return "📊 任务状态: " + state.getStatus() + "\n" +
           "进度: " + state.getProgressPercent() + "%\n" +
           "图片URLs: " + (state.getImageUrls() != null ? state.getImageUrls() : "等待中...");
}

@Tool(description = "List available AI models on the local DrawThings server")
public String listDrawThingsModels() {
    if (!grpcService.isAvailable()) return "❌ gRPC 服务不可用";
    List<ModelInfo> models = grpcService.listModels();
    if (models.isEmpty()) return "📋 未找到模型文件";
    StringBuilder sb = new StringBuilder("📋 DrawThings 可用模型:\n");
    for (int i = 0; i < models.size(); i++) {
        sb.append("  ").append(i + 1).append(". ").append(models.get(i).filename()).append("\n");
    }
    return sb.toString();
}
```

---

## 9. SDUI 工作流节点增强

### 9.1 Txt2ImgGrpcNode

新增独立节点 `platform.txt2img-grpc`，与 HTTP 版本的 `platform.txt2img` 共存：

```java
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "drawthings.grpc", name = "enabled", havingValue = "true")
public class Txt2ImgGrpcNode implements CapabilityNode {

    private final ImageGenGrpcService grpcService;

    @Override
    public String type() { return "platform.txt2img-grpc"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(
                type(), "文生图 (gRPC)", "通过 DrawThings gRPC 流式生成图片, 支持实时进度",
                "platform", "image",
                List.of(
                        new ParamDef("prompt", "string", true, null, "生成提示词"),
                        new ParamDef("negative_prompt", "string", false, "", "负向提示词"),
                        new ParamDef("save_to", "string", true, "image_url", "保存图片URL的变量名"),
                        new ParamDef("poll_interval_ms", "int", false, 2000, "轮询间隔(毫秒)"),
                        new ParamDef("timeout_ms", "int", false, 300000, "超时时间(毫秒)")
                ),
                List.of(
                        new ParamDef("image_url", "string", false, null, "生成的图片URL"),
                        new ParamDef("progress_history", "string", false, null, "进度历史JSON")
                ),
                false, 300000
        );
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        // 1. 解析参数
        String prompt = resolve(str(ctx.resolvedInputs(), "prompt"), ctx);
        String negativePrompt = resolve(str(ctx.resolvedInputs(), "negative_prompt"), ctx);
        String saveTo = str(ctx.resolvedInputs(), "save_to");

        // 2. 提交异步任务
        String taskId = grpcService.submitGeneration(prompt,
                negativePrompt != null ? negativePrompt : "");

        // 3. 轮询等待完成
        long pollInterval = parseInt(ctx.resolvedInputs(), "poll_interval_ms", 2000);
        long timeout = parseInt(ctx.resolvedInputs(), "timeout_ms", 300000);
        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() - start < timeout) {
            TaskState state = grpcService.getTask(taskId);
            if (state == null) return NodeResult.error("任务丢失: " + taskId);

            switch (state.getStatus()) {
                case "COMPLETED":
                    String firstUrl = (state.getImageUrls() != null &&
                            !state.getImageUrls().isEmpty())
                            ? state.getImageUrls().get(0) : "";
                    return NodeResult.completed(Map.of(
                            "image_url", firstUrl,
                            "progress_history", toJson(state.getProgressHistory())
                    ), Set.of(saveTo));
                case "FAILED":
                    return NodeResult.error("生成失败: " + state.getError());
                case "CANCELLED":
                    return NodeResult.error("任务已取消");
                default:
                    break; // 继续轮询
            }

            try { Thread.sleep(pollInterval); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return NodeResult.error("轮询中断");
            }
        }

        return NodeResult.error("任务超时: " + taskId);
    }

    // ... 辅助方法 str(), parseInt(), resolve(), toJson()
}
```

---

## 10. 配置文件

### 10.1 application.yml

```yaml
# ── DrawThings 配置 ──
drawthings:
  # HTTP API (A1111 兼容) —— 现有功能保持
  enabled: true
  base-url: http://127.0.0.1:7860
  timeout: 300000
  default-width: 512
  default-height: 512
  default-steps: 8
  default-cfg-scale: 7.0

  # gRPC API —— 新增
  grpc:
    enabled: true
    host: 127.0.0.1
    port: 7859
    use-tls: false
    shared-secret: ""          # 如果需要认证, 在此填写
    max-message-size: 1073741824  # 1GB, 大图片/模型传输需要
    keep-alive-sec: 30
    task-cleanup-minutes: 60   # 完成后任务状态保留时间
```

### 10.2 application-macos.yml (覆盖)

```yaml
drawthings:
  grpc:
    host: 127.0.0.1
    port: 7859
```

---

## 11. 实施路线图

### Phase 1: 基础设施 (3-4 小时)

```
□ 1.1 获取 proto 文件 → src/main/proto/imageService.proto
□ 1.2 配置 pom.xml: gRPC 依赖 + protobuf-maven-plugin
□ 1.3 mvn compile 验证 proto 编译成功
□ 1.4 创建 GrpcClientConfig (ManagedChannel 管理)
□ 1.5 验证 gRPC 连接: Echo RPC 调用成功
```

**验证点**: 启动 gRPCServerCLI → Java 端调用 `echo()` → 收到服务器响应

### Phase 2: 核心功能 (3-4 小时)

```
□ 2.1 实现 DrawThingsGrpcClient.generateAsync() (流式生成)
□ 2.2 实现 GenerationProgress 解析 (signpost → 进度 DTO)
□ 2.3 实现 ImageGenGrpcService (任务管理 + 文件保存)
□ 2.4 实现 TaskState (异步任务状态追踪)
□ 2.5 端到端测试: 提交任务 → 收到进度回调 → 图片保存成功
```

**验证点**: 发送 prompt → 收到 `sampling(step=1,2,3...)` 进度 → 收到最终图片

### Phase 3: API 层 (2-3 小时)

```
□ 3.1 实现 ImageGenGrpcController (REST 端点)
□ 3.2 增强 ImageGenTool (Agent 工具: 异步生成 + 进度查询 + 模型列表)
□ 3.3 实现 Txt2ImgGrpcNode (SDUI 工作流节点)
□ 3.4 Swagger 文档验证所有端点
```

**验证点**: curl 调用所有端点 → 进度轮询 → 获取图片 URL → 图片可访问

### Phase 4: 模型管理 (1-2 小时)

```
□ 4.1 实现 listModels() (通过 Echo.files 或 FilesExist)
□ 4.2 实现模型信息端点 GET /models
□ 4.3 Agent Tool: listDrawThingsModels()
□ 4.4 (可选) 模型存在性校验 checkFiles()
```

### Phase 5: 优化与文档 (2-3 小时)

```
□ 5.1 过期任务清理 (Scheduled)
□ 5.2 错误处理与重试机制
□ 5.3 日志完善
□ 5.4 集成测试
□ 5.5 README / 运维文档
```

---

## 12. 测试验证

### 12.1 单元测试

```java
@SpringBootTest
class DrawThingsGrpcClientTest {

    @Autowired
    private DrawThingsGrpcClient client;

    @Test
    void testEcho() {
        EchoReply reply = client.echo();
        assertNotNull(reply);
        assertFalse(reply.getMessage().isEmpty());
        System.out.println("Server files: " + reply.getFilesList());
    }

    @Test
    void testListModels() {
        List<ModelInfo> models = client.listModels();
        assertNotNull(models);
        models.forEach(m -> System.out.println("  " + m.filename()));
    }
}
```

### 12.2 端到端测试 (curl)

```bash
# 1. 健康检查
curl http://localhost:8080/api/v1/sdui/image/grpc/health

# 2. 获取服务器信息
curl http://localhost:8080/api/v1/sdui/image/grpc/server

# 3. 列出模型
curl http://localhost:8080/api/v1/sdui/image/grpc/models

# 4. 异步生成图片
TASK=$(curl -s -X POST \
  "http://localhost:8080/api/v1/sdui/image/grpc/generate/async?prompt=a%20cute%20cat" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['taskId'])")
echo "Task: $TASK"

# 5. 轮询进度
for i in $(seq 1 30); do
  curl -s "http://localhost:8080/api/v1/sdui/image/grpc/task/$TASK" \
    | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print(f'{d[\"status\"]}: {d.get(\"progressPercent\",0)}%')"
  sleep 2
done

# 6. 获取结果图片
curl -s "http://localhost:8080/api/v1/sdui/image/grpc/task/$TASK" \
  | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print(d.get('imageUrls'))"
```

---

## 13. 故障排查

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| gRPC 连接拒绝 | gRPCServerCLI 未启动 | 检查端口：`lsof -i :7859` |
| UNAVAILABLE: io exception | TLS 配置不匹配 | 检查 `use-tls: false` 是否与服务端一致 |
| 生成请求超时 | 图片尺寸太大或模型太慢 | 增加超时，减小尺寸 |
| 收到乱码/空响应 | FPY 压缩未关闭 | 确保 `--no-response-compression` |
| 进度一直是 0% | totalSteps 未知 | 在 signpost 中逐步推算步数 |
| Echo 返回空文件列表 | --model-browser 未启用 | 重启 CLI 加 `--model-browser` |
| sharedSecretMissing = true | 服务器需要认证 | 在 drawthings.grpc.shared-secret 填密钥 |

---

## 附录 A: 文件清单

### 新增文件 (~15 个)

```
src/main/proto/
└── imageService.proto                          # gRPC 服务定义

src/main/java/com/zwbd/agentnexus/sdui/image/grpc/
├── GrpcClientConfig.java                       # gRPC Channel 配置
├── DrawThingsGrpcClient.java                   # gRPC 客户端封装
├── dto/
│   ├── GenerationProgress.java                 # 进度 DTO
│   ├── ModelInfo.java                          # 模型信息 DTO
│   └── GrpcGenerationResult.java               # 生成结果 DTO

src/main/java/com/zwbd/agentnexus/sdui/image/
├── ImageGenGrpcService.java                    # gRPC 服务层
├── TaskState.java                               # 异步任务状态
├── ImageGenGrpcController.java                 # REST 控制器
└── node/
    └── Txt2ImgGrpcNode.java                    # SDUI 工作流节点
```

### 修改文件 (~5 个)

```
pom.xml                                         # gRPC 依赖 + proto 插件
src/main/resources/application.yml              # drawthings.grpc 配置
src/main/resources/application-macos.yml        # macOS 覆盖配置
sdui/image/ImageGenTool.java                    # 新增 Agent 工具方法
sdui/image/ImageGenController.java              # (可选) 合并 HTTP + gRPC 端点
```

---

## 附录 B: 参考资料

| 资源 | URL |
|------|-----|
| Proto 文件源码 | `github.com/drawthingsai/draw-things-community` → `Libraries/GRPC/Models/Sources/imageService/imageService.proto` |
| gRPCServerCLI 下载 | `https://github.com/drawthingsai/draw-things-community/releases` |
| npm dt-skill 工具 | `https://www.npmjs.com/package/@mijuu/drawthings` |
| Spring Boot gRPC Starter | `https://grpc-ecosystem.github.io/grpc-spring/` |
| Protobuf Maven Plugin | `https://www.xolstice.org/protobuf-maven-plugin/` |
| FlatBuffers Java | `https://github.com/google/flatbuffers` |
| gRPC Java 官方文档 | `https://grpc.io/docs/languages/java/` |
| DeepWiki API 文档 | `https://deepwiki.com/drawthingsai/draw-things-community/` |
