/**
 * Example usage of the TeleRCtrl protocol parser on STM32 (HAL).
 *
 * Assumes:
 *   - UART RX interrupt calls USART1_IRQHandler
 *   - HAL_UART_Receive_IT(&huart1, &rx_byte, 1) is active
 *   - HAL_GetTick() provides a millisecond counter
 */

#include "protocol.h"
#include "usart.h"          /* or your HAL header */

static tele_protocol_t proto;
static uint8_t rx_byte;

/* ── Call from HAL_UART_RxCpltCallback ── */
void HAL_UART_RxCpltCallback(UART_HandleTypeDef *huart) {
    if (huart->Instance == USART1) {
        if (tele_feed(&proto, rx_byte, HAL_GetTick())) {
            /* A new 9-byte frame has arrived and been parsed.
             * Access fields directly from `proto`. */

            /* ── Matrix key ── */
            if (proto.matrix_key != 0xFF) {
                // Matrix key pressed: 0..11
            }

            /* ── Mode ── */
            // proto.mode_chassis  – 0 = chassis, 1 = task
            // proto.mode_channel  – 0 = ch1, 1 = ch2
            // proto.mode_zone     – 0 = zone1, 1 = zone2, 2 = zone3

            /* ── Function buttons ── */
            if (proto.btn_pump)   { /* pump on */ }
            if (proto.btn_grab)   { /* grab */ }
            if (proto.btn_fix)    { /* fix */ }
            if (proto.btn_light1) { /* light 1 */ }
            if (proto.btn_light2) { /* light 2 */ }
            if (proto.btn_light3) { /* light 3 */ }
            if (proto.btn_toggle) { /* toggle */ }
            if (proto.btn_lock)   { /* lock */ }

            /* ── Axes (apply dead zone if desired) ── */
            int8_t thr = tele_apply_dead_zone(proto.throttle);
            int8_t st  = tele_apply_dead_zone(proto.steering);
            int8_t sx  = tele_apply_dead_zone(proto.strafe);
            int8_t ly  = tele_apply_dead_zone(proto.lift);

            // thr: -100..+100  (or 0 if in dead zone)
            // st:  steering
            // sx:  strafe
            // ly:  lift
        }

        /* Re-arm single-byte RX interrupt */
        HAL_UART_Receive_IT(huart, &rx_byte, 1);
    }
}

/* ── Initialisation ── */
void tele_protocol_init(void) {
    tele_init(&proto);
    HAL_UART_Receive_IT(&huart1, &rx_byte, 1);
}

/* ── Watchdog / connection-loss detection ── */
void tele_protocol_loop(void) {
    /* If no frame arrived for 250 ms, assume link is dead.
     * The Android side sends every 50 ms. */
    if (!tele_alive(&proto, HAL_GetTick(), 250)) {
        // Enter safe state – stop motors, etc.
    }
}
