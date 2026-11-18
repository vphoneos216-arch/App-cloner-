// Native side of the cloning engine.
//
// Three things are done here because the JVM cannot do them at all, or cannot do them without
// shelling out:
//
//   1. Reading a file's SELinux label. The label lives in the `security.selinux` extended
//      attribute. Getting it through a shell means spawning `ls -Z` and parsing its output;
//      lgetxattr returns the exact bytes.
//   2. Reading ownership and mode without following symlinks. java.io.File cannot report a
//      numeric uid, and a clone's data directory is identified precisely by its uid.
//   3. Parsing an ELF header. Reading 20 bytes and checking the architecture is cheaper and
//      clearer here than streaming the file through the JVM.
//
// Every function fails soft: it returns null or an error marker rather than throwing, because
// these paths are frequently unreadable without root and that is an expected answer.

#include <jni.h>

#include <sys/stat.h>
#include <sys/types.h>
#include <sys/xattr.h>
#include <unistd.h>
#include <fcntl.h>

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace {

// RAII for a JNI string, so every early return releases it.
class ScopedUtf {
 public:
  ScopedUtf(JNIEnv* env, jstring value) : env_(env), value_(value) {
    chars_ = value ? env->GetStringUTFChars(value, nullptr) : nullptr;
  }
  ~ScopedUtf() {
    if (chars_) env_->ReleaseStringUTFChars(value_, chars_);
  }
  ScopedUtf(const ScopedUtf&) = delete;
  ScopedUtf& operator=(const ScopedUtf&) = delete;

  const char* get() const { return chars_; }
  bool valid() const { return chars_ != nullptr; }

 private:
  JNIEnv* env_;
  jstring value_;
  const char* chars_;
};

constexpr size_t kElfHeaderBytes = 20;
constexpr size_t kContextMax = 1024;

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_akash_appcloner_engine_nativebridge_NativeBridge_nativeSelinuxContext(
    JNIEnv* env, jclass, jstring j_path) {
  ScopedUtf path(env, j_path);
  if (!path.valid()) return nullptr;

  std::vector<char> buffer(kContextMax, 0);
  // lgetxattr, not getxattr: a symlink's own label is what matters when auditing a data dir.
  ssize_t length = lgetxattr(path.get(), "security.selinux", buffer.data(), buffer.size() - 1);
  if (length < 0) return nullptr;

  // The attribute is commonly stored with a trailing NUL, which must not reach the JVM string.
  while (length > 0 && buffer[static_cast<size_t>(length) - 1] == '\0') --length;
  buffer[static_cast<size_t>(length)] = '\0';
  return env->NewStringUTF(buffer.data());
}

// Returns {uid, gid, mode, size, nlink, isDirectory} or null when the path cannot be stat'ed.
JNIEXPORT jlongArray JNICALL
Java_com_akash_appcloner_engine_nativebridge_NativeBridge_nativeStat(
    JNIEnv* env, jclass, jstring j_path) {
  ScopedUtf path(env, j_path);
  if (!path.valid()) return nullptr;

  struct stat info {};
  if (lstat(path.get(), &info) != 0) return nullptr;

  jlong values[6];
  values[0] = static_cast<jlong>(info.st_uid);
  values[1] = static_cast<jlong>(info.st_gid);
  values[2] = static_cast<jlong>(info.st_mode);
  values[3] = static_cast<jlong>(info.st_size);
  values[4] = static_cast<jlong>(info.st_nlink);
  values[5] = S_ISDIR(info.st_mode) ? 1 : 0;

  jlongArray result = env->NewLongArray(6);
  if (result == nullptr) return nullptr;
  env->SetLongArrayRegion(result, 0, 6, values);
  return result;
}

// Returns {is64Bit, isLittleEndian, machine} or null when the file is not an ELF object.
JNIEXPORT jintArray JNICALL
Java_com_akash_appcloner_engine_nativebridge_NativeBridge_nativeElfHeader(
    JNIEnv* env, jclass, jstring j_path) {
  ScopedUtf path(env, j_path);
  if (!path.valid()) return nullptr;

  int fd = open(path.get(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) return nullptr;

  unsigned char header[kElfHeaderBytes] = {0};
  size_t read_total = 0;
  while (read_total < kElfHeaderBytes) {
    ssize_t n = read(fd, header + read_total, kElfHeaderBytes - read_total);
    if (n <= 0) break;
    read_total += static_cast<size_t>(n);
  }
  close(fd);
  if (read_total < kElfHeaderBytes) return nullptr;

  if (header[0] != 0x7F || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') {
    return nullptr;
  }
  const int elf_class = header[4];           // 1 = 32-bit, 2 = 64-bit
  const int elf_data = header[5];            // 1 = little endian, 2 = big endian
  if ((elf_class != 1 && elf_class != 2) || (elf_data != 1 && elf_data != 2)) return nullptr;

  const bool little_endian = elf_data == 1;
  const int machine = little_endian
                          ? (header[18] | (header[19] << 8))
                          : ((header[18] << 8) | header[19]);

  jint values[3] = {elf_class == 2 ? 1 : 0, little_endian ? 1 : 0, machine};
  jintArray result = env->NewIntArray(3);
  if (result == nullptr) return nullptr;
  env->SetIntArrayRegion(result, 0, 3, values);
  return result;
}

// Proves the library is loaded and which ABI it was built for.
JNIEXPORT jstring JNICALL
Java_com_akash_appcloner_engine_nativebridge_NativeBridge_nativeAbi(JNIEnv* env, jclass) {
#if defined(__aarch64__)
  const char* abi = "arm64-v8a";
#elif defined(__arm__)
  const char* abi = "armeabi-v7a";
#elif defined(__x86_64__)
  const char* abi = "x86_64";
#elif defined(__i386__)
  const char* abi = "x86";
#else
  const char* abi = "unknown";
#endif
  return env->NewStringUTF(abi);
}

// The uid this process runs as, read through the syscall rather than inferred.
JNIEXPORT jint JNICALL
Java_com_akash_appcloner_engine_nativebridge_NativeBridge_nativeGetuid(JNIEnv*, jclass) {
  return static_cast<jint>(getuid());
}

}  // extern "C"
