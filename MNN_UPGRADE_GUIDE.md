# MNN 3.5.0 升级说明

## 概述

本项目已升级支持 **MNN 3.5.0**，新增支持新 **flatbuffers 格式模型** (magic bytes: `0x20`/`0x24`)。

### 格式说明

| Magic Bytes | 格式类型 | 支持的MNN版本 |
|------------|---------|--------------|
| `0x4D 0x4E 0x4E` | 旧版 flatbuffers | MNN 3.4.x |
| `0x20 XX XX XX` | 新版 flatbuffers | MNN 3.5.0+ |
| `0x24 XX XX XX` | 新版 flatbuffers | MNN 3.5.0+ |

## 头文件更新

已更新以下头文件到 MNN 3.5.0：

- `app/src/main/cpp/mnn-src/include/MNN/MNNDefine.h` - MNN版本定义 (3.5.0)
- `app/src/main/cpp/mnn-src/include/MNN/Tensor.hpp` - 张量API
- `app/src/main/cpp/mnn-src/include/llm/llm.hpp` - LLM引擎API

## API 兼容性

MNN 3.5.0 与 3.4.1 API 保持兼容：

```cpp
// 创建LLM实例
MNN::Transformer::Llm* llm = MNN::Transformer::Llm::createLLM(config_path);

// 设置配置
llm->set_config(json_config);

// 加载模型
llm->load();

// 生成回复
llm->response(chat_history, &oss, eos_token, max_tokens);

// 流式生成
llm->generate_init(nullptr, eos_token);
while (!llm->stoped()) {
    llm->generate(1);
    // 获取输出: llm->getContext()->generate_str
}

// 销毁实例
MNN::Transformer::Llm::destroy(llm);
```

## 编译 MNN Android 库

### 方法一: 使用官方 Android 构建脚本

MNN 官方提供了 Android 构建脚本：

```bash
# 克隆 MNN 仓库
git clone https://github.com/alibaba/MNN.git
cd MNN

# 进入 Android 构建目录
cd project/android

# 创建构建目录
mkdir build_64 && cd build_64

# 运行构建脚本
../build_64.sh \
    "-DMNN_LOW_MEMORY=true" \
    "-DMNN_CPU_WEIGHT_DEQUANT_GEMM=true" \
    "-DMNN_BUILD_LLM=true" \
    "-DMNN_SUPPORT_TRANSFORMER_FUSE=true" \
    "-DMNN_ARM82=true" \
    "-DMNN_USE_LOGCAT=true" \
    "-DMNN_OPENCL=true" \
    "-DLLM_SUPPORT_VISION=true" \
    "-DMNN_BUILD_OPENCV=true" \
    "-DMNN_IMGCODECS=true" \
    "-DLLM_SUPPORT_AUDIO=true" \
    "-DMNN_BUILD_AUDIO=true" \
    "-DMNN_BUILD_DIFFUSION=ON" \
    "-DCMAKE_SHARED_LINKER_FLAGS='-Wl,-z,max-page-size=16384'"

# 安装库文件
make install
```

### 方法二: 从 MNNChat APK 提取

如果你已经有 MNN 官方的 Android 应用，可以从中提取预编译库：

```bash
# 下载 MNNChat APK
# https://github.com/alibaba/MNN/releases

# 解压 APK
unzip mnn_chat_xxx.apk -d mnn_extracted

# 提取 .so 文件
cp mnn_extracted/lib/arm64-v8a/libMNN.so app/src/main/jniLibs/arm64-v8a/
cp mnn_extracted/lib/arm64-v8a/libMNN_Express.so app/src/main/jniLibs/arm64-v8a/
cp mnn_extracted/lib/arm64-v8a/libllm.so app/src/main/jniLibs/arm64-v8a/

# 提取头文件 (如需要)
# 头文件位于项目根目录的 include/ 和 transformers/llm/engine/include/
```

### 需要的 .so 文件

确保以下文件存在于 `app/src/main/jniLibs/arm64-v8a/` 目录：

```
libMNN.so          # 核心推理引擎
libMNN_Express.so  # Express 动态图API
libllm.so          # LLM推理库
```

### 可选的 GPU 加速库 (如需 Vulkan/OpenCL)

```
libMNN_Vulkan.so   # Vulkan GPU后端
libMNN_OpenCL.so   # OpenCL GPU后端
```

## 构建项目

确保 .so 文件就位后，使用 Gradle 构建：

```bash
./gradlew assembleDebug
# 或
./gradlew assembleRelease
```

## 模型格式检测

native-lib.cpp 中已添加模型格式自动检测：

```cpp
static std::string checkModelFormat(const std::string& model_dir) {
    // 读取 llm.mnn 头部 4 字节
    // 0x20/0x24 -> 新格式 (MNN 3.5.0+)
    // "MNN"   -> 旧格式 (MNN 3.4.x)
}
```

日志输出示例：

```
Model magic bytes: 0x20 0x00 0x00 0x00
Detected NEW flatbuffers format (0x20) - MNN 3.5.0+
```

## 故障排除

### 问题: 模型加载失败 "createLLM returned nullptr"

可能原因：
1. **MNN库版本不匹配** - 确认使用 MNN 3.5.0+ 编译的库
2. **模型文件损坏** - 检查 llm.mnn, llm.mnn.weight 等文件
3. **内存不足** - LLM模型需要足够内存

解决方案：
```bash
# 检查模型文件
ls -la <model_dir>/
hexdump -C <model_dir>/llm.mnn | head

# 检查可用内存
cat /proc/meminfo
```

### 问题: 模型格式不兼容

如果模型使用旧格式但库是新版本，或反之：
- 旧模型 + 旧库 ✅ 兼容
- 新模型 + 新库 ✅ 兼容
- 旧模型 + 新库 ❌ 不兼容
- 新模型 + 旧库 ❌ 不兼容

解决方案：使用匹配版本的库

### 问题: 编译找不到 MNN 头文件

确保头文件路径正确：
```cmake
set(MNN_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/mnn-src/include)
target_include_directories(localai-jni PRIVATE ${MNN_INCLUDE_DIR})
```

## 更多信息

- [MNN GitHub 仓库](https://github.com/alibaba/MNN)
- [MNN LLM 文档](https://mnn-docs.readthedocs.io/en/latest/transformers/llm.html)
- [MNN Release Notes](https://github.com/alibaba/MNN/releases)
