#include "protocol.h"

/* ───── Parser states ───── */
enum {
    ST_WAIT_H0,
    ST_WAIT_H1,
    ST_COLLECT_PAYLOAD,
    ST_WAIT_TAIL,
    ST_CONFIG_WAIT_PAYLOAD,
    ST_CONFIG_WAIT_TAIL,
};

/* ───── Parse a received 9-byte telemetry frame ───── */

static void parse_control(tele_control_t *c, const uint8_t *b) {
    uint8_t v   = b[2];
    uint8_t mat = (v >> 4) & 0x0F;
    uint8_t md  = v & 0x0F;
    c->matrix_key    = (mat <= 11) ? mat : 0xFF;
    c->mode_zone     = (md >> 2) & 0x03;
    c->mode_channel  = (md >> 1) & 0x01;
    c->mode_chassis  =  md        & 0x01;

    uint8_t f = b[3];
    c->btn_pump    = (f >> 0) & 1;
    c->btn_grab    = (f >> 1) & 1;
    c->btn_fix     = (f >> 2) & 1;
    c->btn_light1  = (f >> 3) & 1;
    c->btn_light2  = (f >> 4) & 1;
    c->btn_light3  = (f >> 5) & 1;
    c->btn_toggle  = (f >> 6) & 1;
    c->btn_lock    = (f >> 7) & 1;

    c->throttle = (int8_t)b[4];
    c->steering = (int8_t)b[5];
    c->strafe   = (int8_t)b[6];
    c->lift     = (int8_t)b[7];
}

/* ───── Parse a received 5-byte config frame ───── */

static void parse_config(tele_config_t *c, const uint8_t *b) {
    for (int i = 0; i < CELL_COUNT; i++) {
        int byte_idx = i / 4;
        int shift    = (3 - (i % 4)) * 2;
        c->cells[i] = (b[1 + byte_idx] >> shift) & 0x03;
    }
    c->received = true;
}

/* ───── Public API ───── */

void tele_init(tele_protocol_t *p) {
    tele_reset(p);
}

void tele_reset(tele_protocol_t *p) {
    p->__parse_state  = ST_WAIT_H0;
    p->__buf_idx      = 0;
    p->__last_frame_ms = 0;
    p->ctrl.matrix_key = 0xFF;
    p->cfg.received    = false;
}

void tele_config_ack(tele_protocol_t *p) {
    p->cfg.received = false;
}

bool tele_feed(tele_protocol_t *p, uint8_t byte_, uint32_t tick_ms) {
    switch (p->__parse_state) {

    /* ── Wait for first header byte ── */
    case ST_WAIT_H0:
        if (byte_ == FRAME_HEADER_0) {
            p->__buf[0] = byte_;
            p->__buf_idx = 1;
            p->__parse_state = ST_WAIT_H1;
        } else if (byte_ == CONFIG_HEADER) {
            p->__buf[0] = byte_;
            p->__buf_idx = 1;
            p->__parse_state = ST_CONFIG_WAIT_PAYLOAD;
        }
        break;

    /* ── Wait for second header byte (telemetry only) ── */
    case ST_WAIT_H1:
        if (byte_ == FRAME_HEADER_1) {
            p->__buf[1] = byte_;
            p->__buf_idx = 2;
            p->__parse_state = ST_COLLECT_PAYLOAD;
        } else if (byte_ == FRAME_HEADER_0) {
            p->__buf[0] = byte_;
            p->__buf_idx = 1;
        } else {
            p->__parse_state = ST_WAIT_H0;
        }
        break;

    /* ── Collect telemetry payload bytes 2..7 ── */
    case ST_COLLECT_PAYLOAD:
        p->__buf[p->__buf_idx++] = byte_;
        if (p->__buf_idx == 8) {
            p->__parse_state = ST_WAIT_TAIL;
        }
        break;

    /* ── Expect telemetry tail ── */
    case ST_WAIT_TAIL:
        if (byte_ == FRAME_TAIL) {
            p->__buf[8] = byte_;
            p->__last_frame_ms = tick_ms;
            parse_control(&p->ctrl, p->__buf);
            p->__parse_state = ST_WAIT_H0;
            p->__buf_idx = 0;
            return true;                     /* telemetry frame done */
        }
        p->__parse_state = ST_WAIT_H0;
        p->__buf_idx = 0;
        break;

    /* ── Collect config payload bytes 1..3 ── */
    case ST_CONFIG_WAIT_PAYLOAD:
        p->__buf[p->__buf_idx++] = byte_;
        if (p->__buf_idx == 4) {            /* collected 0xCC + 3 payload */
            p->__parse_state = ST_CONFIG_WAIT_TAIL;
        }
        break;

    /* ── Expect config tail ── */
    case ST_CONFIG_WAIT_TAIL:
        if (byte_ == FRAME_TAIL) {
            p->__buf[4] = byte_;
            p->__last_frame_ms = tick_ms;
            parse_config(&p->cfg, p->__buf);
            p->__parse_state = ST_WAIT_H0;
            p->__buf_idx = 0;
            return true;                     /* config frame done */
        }
        p->__parse_state = ST_WAIT_H0;
        p->__buf_idx = 0;
        break;
    }

    return false;
}

bool tele_alive(tele_protocol_t *p, uint32_t tick_ms, uint32_t timeout_ms) {
    if (p->__last_frame_ms == 0) return false;
    return (tick_ms - p->__last_frame_ms) < timeout_ms;
}
