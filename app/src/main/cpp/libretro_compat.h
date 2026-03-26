#ifndef LIBRETRO_COMPAT_H__
#define LIBRETRO_COMPAT_H__

#include "libretro.h"

typedef void (*retro_init_t)(void);
typedef void (*retro_deinit_t)(void);
typedef void (*retro_run_t)(void);
typedef bool (*retro_load_game_t)(const struct retro_game_info *);
typedef void (*retro_unload_game_t)(void);
typedef void (*retro_set_environment_t)(retro_environment_t);
typedef void (*retro_set_video_refresh_t)(retro_video_refresh_t);
typedef void (*retro_set_audio_sample_t)(retro_audio_sample_t);
typedef void (*retro_set_audio_sample_batch_t)(retro_audio_sample_batch_t);
typedef void (*retro_set_input_poll_t)(retro_input_poll_t);
typedef void (*retro_set_input_state_t)(retro_input_state_t);
typedef void (*retro_get_system_info_t)(struct retro_system_info *);
typedef void (*retro_get_system_av_info_t)(struct retro_system_av_info *);
typedef size_t (*retro_serialize_size_t)(void);
typedef bool (*retro_serialize_t)(void *, size_t);
typedef bool (*retro_unserialize_t)(const void *, size_t);
typedef void *(*retro_get_memory_data_t)(unsigned);
typedef size_t (*retro_get_memory_size_t)(unsigned);
typedef void (*retro_reset_t)(void);

#endif
