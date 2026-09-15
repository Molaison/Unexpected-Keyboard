// Exercise the same borrowed-descriptor/offset boundary used by Android APKs.
#include <cassert>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include "pinyinime.h"

int main(int argc, char **argv) {
  assert(argc == 3);
  const std::string wrapped = std::string(argv[2]) + "/fd-asset.bin";
  const std::string user = std::string(argv[2]) + "/fd-user.dat";
  FILE *source = std::fopen(argv[1], "rb");
  FILE *output = std::fopen(wrapped.c_str(), "wb");
  assert(source && output);
  const char prefix[] = "APK entry prefix";
  assert(std::fwrite(prefix, 1, sizeof(prefix), output) == sizeof(prefix));
  char buffer[16384];
  size_t length = 0, n;
  while ((n = std::fread(buffer, 1, sizeof(buffer), source)) != 0) {
    assert(std::fwrite(buffer, 1, n, output) == n);
    length += n;
  }
  assert(!std::ferror(source) && length > 1000000);
  assert(std::fclose(source) == 0);
  assert(std::fwrite("suffix", 1, 6, output) == 6);
  assert(std::fclose(output) == 0);

  int fd = open(wrapped.c_str(), O_RDONLY);
  assert(fd >= 0);
  assert(ime_pinyin::im_open_decoder_fd(fd, sizeof(prefix), length, user.c_str()));
  assert(fcntl(fd, F_GETFD) != -1); // The caller still owns its descriptor.
  ime_pinyin::im_reset_search();
  assert(ime_pinyin::im_search("nihao", 5) > 0);
  ime_pinyin::char16 candidate[40] = {};
  const ime_pinyin::char16 expected[] = {0x4f60, 0x597d, 0};
  assert(ime_pinyin::im_get_candidate(0, candidate, 40));
  assert(ime_pinyin::utf16_strcmp(candidate, expected) == 0);
  ime_pinyin::im_close_decoder();
  assert(fcntl(fd, F_GETFD) != -1);
  assert(lseek(fd, 0, SEEK_SET) == 0);
  assert(read(fd, buffer, sizeof(prefix)) == sizeof(prefix));
  assert(std::memcmp(buffer, prefix, sizeof(prefix)) == 0);
  assert(close(fd) == 0);
  assert(unlink(wrapped.c_str()) == 0);
  assert(unlink(user.c_str()) == 0);
  std::puts("APK-style dictionary offset and FD ownership tests passed");
}
