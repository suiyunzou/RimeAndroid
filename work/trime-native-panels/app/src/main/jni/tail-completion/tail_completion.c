// SPDX-License-Identifier: GPL-3.0-or-later
#include <dlfcn.h>
#include <jni.h>
#include <string.h>

#include "rime_api.h"

// Called only on the existing serial Rime dispatcher. The original session's
// input, candidate indexes, and commit history are never used as scratch space.
static RimeApi* api;
static RimeSessionId session;
static const char session_property[] = "_trime_tail_completion_query";

static int owns_session(void) {
  char marker[8] = {0};
  // Session IDs can be recycled after redeployment or stale-session cleanup.
  return session && api->find_session(session) &&
      api->get_property(session, session_property, marker, sizeof(marker)) &&
      strcmp(marker, "owned") == 0;
}

static int prepare_session(void) {
  if (!api) {
    void* library = dlopen("librime_jni.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) return 0;
    RimeApi* (*get_api)(void) = (RimeApi* (*)(void))dlsym(library, "rime_get_api");
    if (!get_api) {
      dlclose(library);
      return 0;
    }
    api = get_api();
  }
  if (!RIME_PROVIDED(api, set_input) ||
      !RIME_PROVIDED(api, get_candidate_preview) ||
      !RIME_PROVIDED(api, free_candidate_preview) ||
      api->is_maintenance_mode()) return 0;
  if (!owns_session()) {
    session = api->create_session();
    if (!session) return 0;
    if (!api->select_schema(session, "rime_ice")) {
      api->destroy_session(session);
      session = 0;
      return 0;
    }
    api->set_option(session, "ascii_mode", False);
    api->set_option(session, "soft_cursor", False);
    api->set_option(session, "emoji", False);
    api->set_option(session, "prediction", False);
    api->set_option(session, "smart_correction", False);
    api->set_option(session, "smart_personalization", False);
    api->set_option(session, "smart_sensitive_input", False);
    api->set_property(session, session_property, "owned");
  }
  return 1;
}

static int spelling_matches(const char* preedit, const char* spelling,
                            const char* tail) {
  if (!preedit) return 0;
  const char* token = preedit;
  for (const char* p = preedit; *p; ++p) {
    if (*p == ' ' || *p == '\'') {
      token = p + 1;
    } else {
      if (*p != *spelling) return 0;
      ++spelling;
    }
  }
  return !*spelling && strcmp(token, tail) == 0;
}

static int han_text(const char* text) {
  if (!text) return 0;
  int count = 0;
  for (const unsigned char* p = (const unsigned char*)text; *p; p += 3) {
    if ((*p & 0xf0) != 0xe0 || (p[1] & 0xc0) != 0x80 ||
        (p[2] & 0xc0) != 0x80) return 0;
    unsigned int cp = ((*p & 0x0f) << 12) | ((p[1] & 0x3f) << 6) | (p[2] & 0x3f);
    if (cp < 0x3400 || cp > 0x9fff) return 0;
    ++count;
  }
  return count >= 2;
}

JNIEXPORT jstring JNICALL
Java_com_osfans_trime_ime_smart_TailCompletionNative_queryNative(
    JNIEnv* env, jobject object, jstring input, jstring syllable,
    jboolean traditional) {
  (void)object;
  if (!prepare_session()) return NULL;
  const char* spelling = (*env)->GetStringUTFChars(env, input, NULL);
  if (!spelling) return NULL;
  const char* tail = (*env)->GetStringUTFChars(env, syllable, NULL);
  jstring result = NULL;
  api->clear_composition(session);
  api->set_option(session, "traditionalization", traditional);
  if (tail && api->set_input(session, spelling)) {
    RIME_STRUCT(RimeContext, ctx);
    if (api->get_context(session, &ctx)) {
      RIME_STRUCT(RimeCandidatePreview, preview);
      if (spelling_matches(ctx.composition.preedit, spelling, tail) &&
          api->get_candidate_preview(session, &preview)) {
        if (han_text(preview.selected_text) &&
            (!preview.text_before_selection || !*preview.text_before_selection) &&
            (!preview.text_after_selection || !*preview.text_after_selection)) {
          result = (*env)->NewStringUTF(env, preview.selected_text);
        }
        api->free_candidate_preview(&preview);
      }
      api->free_context(&ctx);
    }
  }
  api->clear_composition(session);
  if (tail) (*env)->ReleaseStringUTFChars(env, syllable, tail);
  (*env)->ReleaseStringUTFChars(env, input, spelling);
  return result;
}
