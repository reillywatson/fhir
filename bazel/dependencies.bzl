""" Function for loading dependencies of the FhirProto library """

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

def fhirproto_dependencies(core_lib = False):
    """ Sets up FhirProto dependencies

    Args:
      core_lib: Whether or not this is the core FHIR lib github.com/google/fhir
    """

    main_fhir_location = "" if core_lib else "@com_google_fhir"

    http_archive(
        name = "com_google_absl",
        sha256 = "61d0af0262a0131bb8917fcb883e5e831ee5ad1535433f2f13f85906d1607f81",
        strip_prefix = "abseil-cpp-20230125.1",
        urls = ["https://github.com/abseil/abseil-cpp/archive/refs/tags/20230125.1.zip"],
    )

    http_archive(
        name = "rules_jvm_external",
        strip_prefix = "rules_jvm_external-2.1",
        sha256 = "515ee5265387b88e4547b34a57393d2bcb1101314bcc5360ec7a482792556f42",
        url = "https://github.com/bazelbuild/rules_jvm_external/archive/2.1.zip",
    )

    http_archive(
        name = "io_bazel_rules_closure",
        sha256 = "7d206c2383811f378a5ef03f4aacbcf5f47fd8650f6abbc3fa89f3a27dd8b176",
        strip_prefix = "rules_closure-0.10.0",
        urls = [
            "https://github.com/bazelbuild/rules_closure/archive/0.10.0.tar.gz",
        ],
    )

    http_archive(
        name = "rules_proto",
        sha256 = "602e7161d9195e50246177e7c55b2f39950a9cf7366f74ed5f22fd45750cd208",
        strip_prefix = "rules_proto-97d8af4dc474595af3900dd85cb3a29ad28cc313",
        urls = [
            "https://mirror.bazel.build/github.com/bazelbuild/rules_proto/archive/97d8af4dc474595af3900dd85cb3a29ad28cc313.tar.gz",
            "https://github.com/bazelbuild/rules_proto/archive/97d8af4dc474595af3900dd85cb3a29ad28cc313.tar.gz",
        ],
    )

    http_archive(
        name = "rules_python",
        url = "https://github.com/bazelbuild/rules_python/releases/download/0.1.0/rules_python-0.1.0.tar.gz",
        sha256 = "b6d46438523a3ec0f3cead544190ee13223a52f6a6765a29eae7b7cc24cc83a0",
    )

    http_archive(
        name = "rules_cc",
        urls = ["https://github.com/bazelbuild/rules_cc/archive/bf6a32cff59d22305c37361ca6fea752df8fdd59.zip"],
        strip_prefix = "rules_cc-bf6a32cff59d22305c37361ca6fea752df8fdd59",
        sha256 = "3bb877a515252877080d68d919f39c54e18c74b421ec10831a1d17059cae86bf",
    )

    # Used for the FHIRPath parser runtime.
    http_archive(
        name = "antlr_cc_runtime",
        url = "https://www.antlr.org/download/antlr4-cpp-runtime-4.7.1-source.zip",
        sha256 = "23bebc0411052a260f43ae097aa1ab39869eb6b6aa558b046c367a4ea33d1ccc",
        strip_prefix = "runtime/src",
        build_file = main_fhir_location + "//bazel:antlr.BUILD",
    )

    http_archive(
        name = "bazel_skylib",
        sha256 = "b8a1527901774180afc798aeb28c4634bdccf19c4d98e7bdd1ce79d1fe9aaad7",
        urls = ["https://github.com/bazelbuild/bazel-skylib/releases/download/1.4.1/bazel-skylib-1.4.1.tar.gz"],
    )

    http_archive(
        name = "io_bazel_rules_go",
        sha256 = "dd926a88a564a9246713a9c00b35315f54cbd46b31a26d5d8fb264c07045f05d",
        urls = [
            "https://mirror.bazel.build/github.com/bazelbuild/rules_go/releases/download/v0.38.1/rules_go-v0.38.1.zip",
            "https://github.com/bazelbuild/rules_go/releases/download/v0.38.1/rules_go-v0.38.1.zip",
        ],
    )

    # Used for go_repository bazel rules for go dependencies.
    http_archive(
        name = "bazel_gazelle",
        sha256 = "ecba0f04f96b4960a5b250c8e8eeec42281035970aa8852dda73098274d14a1d",
        urls = [
            "https://mirror.bazel.build/github.com/bazelbuild/bazel-gazelle/releases/download/v0.29.0/bazel-gazelle-v0.29.0.tar.gz",
            "https://github.com/bazelbuild/bazel-gazelle/releases/download/v0.29.0/bazel-gazelle-v0.29.0.tar.gz",
        ],
    )

    http_archive(
        name = "com_google_protobuf",
        sha256 = "da89e968e662c5ae3ca54398a537899dfd9354c0f72712e82a27df925967ae97",
        url = "https://github.com/protocolbuffers/protobuf/archive/02955622cce1e4cbd8337a76269e2b81f39bee09.tar.gz",
        strip_prefix = "protobuf-02955622cce1e4cbd8337a76269e2b81f39bee09",
    )

    http_archive(
        name = "nlohmann_json",
        url = "https://github.com/nlohmann/json/archive/v3.7.3.zip",
        strip_prefix = "json-3.7.3",
        sha256 = "e109cd4a9d1d463a62f0a81d7c6719ecd780a52fb80a22b901ed5b6fe43fb45b",
        build_file_content = """cc_library(name = "json",
                                           visibility = ["//visibility:public"],
                                           hdrs = glob([
                                               "include/nlohmann/*.hpp",
                                               "include/nlohmann/**/*.hpp",
                                               ]),
                                           includes = ["include"],
                                          )""",
    )

    http_archive(
        name = "libarchive",
        build_file = main_fhir_location + "//bazel/buildfiles:libarchive.BUILD",
        sha256 = "c676146577d989189940f1959d9e3980d28513d74eedfbc6b7f15ea45fe54ee2",
        strip_prefix = "libarchive-3.6.1",
        urls = [
            "https://github.com/libarchive/libarchive/releases/download/v3.6.1/libarchive-3.6.1.tar.gz",
        ],
    )

    http_archive(
        name = "icu",
        sha256 = "dfc62618aa4bd3ca14a3df548cd65fe393155edd213e49c39f3a30ccd618fc27",
        strip_prefix = "icu-release-64-2",
        build_file = main_fhir_location + "//bazel/buildfiles:icu.BUILD",
        urls = [
            "https://storage.googleapis.com/mirror.tensorflow.org/github.com/unicode-org/icu/archive/release-64-2.zip",
            "https://github.com/unicode-org/icu/archive/release-64-2.zip",
        ],
    )

    http_archive(
        name = "com_googlesource_code_re2",
        sha256 = "6f4c8514249cd65b9e85d3e6f4c35595809a63ad71c5d93083e4d1dcdf9e0cd6",
        strip_prefix = "re2-2020-08-01",
        urls = [
            "https://github.com/google/re2/archive/2020-08-01.tar.gz",
        ],
    )

    http_archive(
        name = "com_google_googletest",
        sha256 = "ad7fdba11ea011c1d925b3289cf4af2c66a352e18d4c7264392fead75e919363",
        strip_prefix = "googletest-1.13.0",
        urls = [
            "https://github.com/google/googletest/archive/refs/tags/v1.13.0.tar.gz",
        ],
    )

    http_archive(
        name = "com_github_gflags_gflags",
        sha256 = "34af2f15cf7367513b352bdcd2493ab14ce43692d2dcd9dfc499492966c64dcf",
        strip_prefix = "gflags-2.2.2",
        urls = [
            "https://mirror.bazel.build/github.com/gflags/gflags/archive/v2.2.2.tar.gz",
            "https://github.com/gflags/gflags/archive/v2.2.2.tar.gz",
        ],
    )

    http_archive(
        name = "com_github_glog_glog",
        strip_prefix = "glog-0a2e5931bd5ff22fd3bf8999eb8ce776f159cda6",
        sha256 = "58c9b3b6aaa4dd8b836c0fd8f65d0f941441fb95e27212c5eeb9979cfd3592ab",
        urls = [
            "https://github.com/google/glog/archive/0a2e5931bd5ff22fd3bf8999eb8ce776f159cda6.zip",
        ],
    )

    http_archive(
        name = "six_archive",
        urls = [
            "https://pypi.python.org/packages/source/s/six/six-1.16.0.tar.gz",
            "http://mirror.bazel.build/pypi.python.org/packages/source/s/six/six-1.16.0.tar.gz",
        ],
        sha256 = "1e61c37477a1626458e36f7b1d82aa5c9b094fa4802892072e49de9c60c4c926",
        strip_prefix = "six-1.16.0",
        build_file = main_fhir_location + "//bazel/buildfiles:six.BUILD",
    )
