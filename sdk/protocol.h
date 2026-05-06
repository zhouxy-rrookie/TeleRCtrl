#ifndef TELERCTRL_PROTOCOL_H
#define TELERCTRL_PROTOCOL_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ───── Frame constants ───── */

#define FRAME_LEN           9
#define CONFIG_FRAME_LEN    5
#define FRAME_HEADER_0      0x5B
#define FRAME_HEADER_1      0x5B
#define CONFIG_HEADER       0xCC
#define FRAME_TAIL          0x2B

#define AXIS_DEAD_ZONE      14
#define CELL_COUNT          12

/* ───── Telemetry (9-byte, 50 ms) ───── */

typedef struct {
    uint8_t matrix_key;          /* 0..11, 0xFF if none */
    uint8_t mode_zone;           /* 0..2 */
    uint8_t mode_channel;        /* 0..1 */
    uint8_t mode_chassis;        /* 0..1 */

    bool    btn_pump;
    bool    btn_grab;
    bool    btn_fix;
    bool    btn_light1;
    bool    btn_light2;
    bool    btn_light3;
    bool    btn_toggle;
    bool    btn_lock;

    int8_t  throttle;            /* -100..+100 */
    int8_t  steering;
    int8_t  strafe;
    int8_t  lift;
} tele_control_t;

/* ───── Config (5-byte, on demand) ───── */

typedef struct {
    uint8_t cells[CELL_COUNT];   /* each 0..3: 0=default 1=R1 2=R2 3=OFF */
    bool    received;            /* true after a fresh config frame */
} tele_config_t;

/* ───── Combined parser state ───── */

typedef struct {
    tele_control_t ctrl;
    tele_config_t  cfg;

    /* internal – parser state machine */
    int     __parse_state;
    int     __buf_idx;
    uint8_t __buf[FRAME_LEN];
    uint32_t __last_frame_ms;
} tele_protocol_t;

/* ───── API ───── */

void  tele_init(tele_protocol_t *p);
bool  tele_feed(tele_protocol_t *p, uint8_t byte_, uint32_t tick_ms);
bool  tele_alive(tele_protocol_t *p, uint32_t tick_ms, uint32_t timeout_ms);
void  tele_reset(tele_protocol_t *p);
void  tele_config_ack(tele_protocol_t *p);

/* ───── Helpers ───── */

static inline int8_t tele_apply_dead_zone(int8_t val) {
    if (val > -AXIS_DEAD_ZONE && val < AXIS_DEAD_ZONE) return 0;
    return val;
}

static inline float tele_axis_to_float(int8_t val) {
    return (float)val / 100.0f;
}

#ifdef __cplusplus
}
#endif

#endif /* TELERCTRL_PROTOCOL_H */
