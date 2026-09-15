// Unexpected Keyboard dictionary tool. AOSP decoder: see NOTICE.
#include <cstdio>
#include "include/dicttrie.h"

int main(int argc, char **argv) {
  if (argc != 3) {
    std::fprintf(stderr, "Usage: dictionary_builder RAW_UTF16 OUTPUT_DAT\n");
    return 2;
  }
  ime_pinyin::DictTrie dictionary;
  if (!dictionary.build_dict(argv[1], NULL) || !dictionary.save_dict(argv[2])) {
    std::fprintf(stderr, "Failed to build pinyin dictionary\n");
    return 1;
  }
  return 0;
}
