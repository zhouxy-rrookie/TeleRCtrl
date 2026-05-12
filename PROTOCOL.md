# TeleRCtrl 通信协议 v1.10

## 物理层

- 接口：USB 串口 / 蓝牙 SPP (UUID `00001101-0000-1000-8000-00805F9B34FB`)
- 波特率：115200，8N1
- 帧长：统一 9 字节

---

## 帧格式总览

| 帧类型 | 帧头 | 帧尾 | 频率 |
|--------|------|------|------|
| 控制帧 | `5B 5B` | `2B` | 50ms |
| 配置帧 | `5C 5C` | `2C` | 按需 |

---

## 一、控制帧 (9 Bytes)

```
[5B] [5B] [B2] [B3] [B4] [B5] [B6] [B7] [2B]
```

### B2 — 矩阵按键 + 模式

```
B2 = [matrix:4bit] [mode:4bit]
```

#### matrix (高4位，0~15)

**一区** — 2×4×2 编码：

```
matrixVal = takePos × 8 + liftPos × 2 + rodPos
```

| 参数 | 位 | 值 |
|------|-----|-----|
| takePos | bit3 | 0=取杆, 1=收杆 |
| liftPos | bit2:1 | 0=低, 1=中, 2=高, 3=换杆空 |
| rodPos | bit0 | 0=换杆1, 1=换杆2 |

liftPos=3 时 rodPos 忽略。示例：

| 组合 | take | lift | rod | matrixVal |
|------|------|------|-----|-----------|
| 取杆+抬升低+换杆1 | 0 | 0 | 0 | 0 |
| 取杆+抬升中+换杆2 | 0 | 1 | 1 | 3 |
| 收杆+抬升高+换杆1 | 1 | 2 | 0 | 12 |
| 收杆+抬升低+换杆空 | 1 | 3 | 0 | 14 |

**二区** — 选项索引（0~5）：

| matrixVal | 选项 |
|-----------|------|
| 0 | 梅林低 |
| 1 | 梅林中 |
| 2 | 梅林高 |
| 3 | 回收 |
| 4 | 放回 |
| 5 | 越区 |

**三区** — 选项索引（0~6）：

| matrixVal | 选项 |
|-----------|------|
| 0 | R2高 |
| 1 | R2低 |
| 2 | 攻击低 |
| 3 | 攻击高 |
| 4 | 放块 |
| 5 | 捡块 |
| 6 | 放杆 |

#### mode (低4位)

```
mode = switchZone × 4 + switchChannel × 2 + switchChassis
```

| switchZone | switchChannel | switchChassis | mode |
|------------|---------------|---------------|------|
| 0 (一区) | 0 | 0 | 0 |
| 0 | 0 | 1 | 1 |
| 0 | 1 | 0 | 2 |
| 0 | 1 | 1 | 3 |
| 1 (二区) | 0 | 0 | 4 |
| 1 | 0 | 1 | 5 |
| 1 | 1 | 0 | 6 |
| 1 | 1 | 1 | 7 |
| 2 (三区) | 0 | 0 | 8 |
| 2 | 0 | 1 | 9 |
| 2 | 1 | 0 | 10 |
| 2 | 1 | 1 | 11 |

### B3 — 功能按钮 (8 Buttons)

```
B3 = [lock:1] [toggle:1] [light3:1] [light2:1] [light1:1] [fix:1] [grab:1] [pump:1]
```

| 位 | 功能 |
|----|------|
| bit0 | 水泵 |
| bit1 | 抓取 |
| bit2 | 固定 |
| bit3 | 灯1 |
| bit4 | 灯2 |
| bit5 | 灯3 |
| bit6 | 切换 |
| bit7 | 锁定 |

### B4~B7 — 摇杆轴 (int8, -100~100)

| 字节 | 轴 | 死区 |
|------|-----|------|
| B4 | 油门 (axisY) | 无 |
| B5 | 转向 (axisYaw) | ±14 |
| B6 | 横移 (axisX) | ±14 |
| B7 | 升降 (axisJoystickY) | ±14 |

---

## 二、配置帧 (9 Bytes)

```
[5C] [5C] [p0] [p1] [p2] [00] [00] [00] [2C]
```

### Payload 编码

12 个 cell × 2bit = 24bit = 3 字节，高位在前：

```
p0 = [cell_0:2] [cell_1:2] [cell_2:2] [cell_3:2]
p1 = [cell_4:2] [cell_5:2] [cell_6:2] [cell_7:2]
p2 = [cell_8:2] [cell_9:2] [cell_10:2][cell_11:2]
```

cell 值：0=默认/未选，1=R1/选项1，2=R2/选项2，3=OFF/选项3

### Cell 映射表

| Cell | 一区 | 二区 | 三区 |
|------|------|------|------|
| 0 | 1=取杆 2=收杆 | 1=梅林低 | 1=R2高 |
| 1 | 1=抬升低 2=中 3=高 | 1=梅林中 | 1=R2低 |
| 2 | 0=空 1=换杆1 2=换杆2 | 1=梅林高 | 1=攻击低 |
| 3 | 0 | 1=回收 | 1=攻击高 |
| 4 | 0 | 1=放回 | 1=放块 |
| 5 | 0 | 1=越区 | 1=捡块 |
| 6 | 0 | 0 | 1=放杆 |
| 7~11 | 0 | 0 | 0 |

### 选取规则

| 区域 | 规则 |
|------|------|
| 一区 | cell[0], cell[1], cell[2] 各自独立；cell[2] 支持取消（再次点击→0） |
| 二区 | cell[0~5] 六选一 |
| 三区 | cell[0~3] 四选一，cell[4~6] 三选一 |

### 打包示例

**一区**（取杆 + 抬升中 + 换杆2）：

```
cell[0]=1, cell[1]=2, cell[2]=2, 其余=0

p0 = (1<<6) | (2<<4) | (2<<2) = 0x40 | 0x20 | 0x08 = 0x68
p1 = 0x00, p2 = 0x00

帧: 5C 5C 68 00 00 00 00 00 2C
```

**二区**（梅林中）：

```
cell[1]=1, 其余=0

p0 = (1<<4) = 0x10

帧: 5C 5C 10 00 00 00 00 00 2C
```

**三区**（R2低 + 放杆）：

```
cell[1]=1, cell[6]=1, 其余=0

p0 = (1<<4) = 0x10
p1 = (1<<2) = 0x08

帧: 5C 5C 10 08 00 00 00 00 2C
```

---

## 三、STM32 参考实现

```c
#include "protocol.h"

// 控制帧接收
void parse_control(tele_control_t *c, const uint8_t *b) {
    uint8_t v   = b[2];
    c->matrix_key    = (v >> 4) & 0x0F;
    c->mode_zone     = ((v & 0x0F) >> 2) & 0x03;
    c->mode_channel  = ((v & 0x0F) >> 1) & 0x01;
    c->mode_chassis  = (v & 0x0F) & 0x01;

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

// 配置帧接收
void parse_config(tele_config_t *c, const uint8_t *b) {
    for (int i = 0; i < 12; i++) {
        int byte_idx = i / 4;
        int shift    = (3 - (i % 4)) * 2;
        c->cells[i] = (b[2 + byte_idx] >> shift) & 0x03;
    }
    c->received = true;
}

// 一区解码 matrixVal
static inline uint8_t zone1_take(uint8_t m)   { return (m >> 3) & 1; }
static inline uint8_t zone1_lift(uint8_t m)   { return (m >> 1) & 3; }
static inline uint8_t zone1_rod(uint8_t m)    { return m & 1; }
```
