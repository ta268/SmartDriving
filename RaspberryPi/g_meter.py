#!/usr/bin/env python3
"""
MPU6050 G-Meter & BLE Transmitter with SQLite Buffering & Dashcam

依存ライブラリ追加:
    sudo apt-get install python3-picamera
    (※もしくは pip3 install picamera)
"""

import json
import math
import os
import sqlite3
import sys
import threading
import time
import subprocess
from datetime import datetime

# --- カメラ (picamera/picamera2) インポートおよびフォールバック設定 ---
PICAMERA_AVAILABLE = False
PICAMERA_VERSION = None

try:
    from picamera2 import Picamera2
    from picamera2.encoders import H264Encoder
    from picamera2.outputs import CircularOutput
    
    # カメラの存在・接続テスト
    test_cam = Picamera2()
    test_cam.close()
    
    PICAMERA_AVAILABLE = True
    PICAMERA_VERSION = 2
    print("[Info] picamera2 is available. Running in PiCamera2 Mode.")
except Exception:
    try:
        import picamera
        test_cam = picamera.PiCamera()
        test_cam.close()
        
        PICAMERA_AVAILABLE = True
        PICAMERA_VERSION = 1
        print("[Info] picamera (legacy) is available. Running in Legacy PiCamera Mode.")
    except Exception:
        PICAMERA_AVAILABLE = False
        print("[Warning] Camera library (picamera2/picamera) failed to initialize (is camera connected?). Running in Mock Dashcam Mode.")

# --- mpu6050 インポートおよびフォールバック設定 ---
try:
    from mpu6050 import mpu6050

    MPU6050_AVAILABLE = True
except Exception:
    MPU6050_AVAILABLE = False
    print("[Warning] mpu6050 is not available. Running in Mock Sensor Mode.")

# --- bluezero / GLib インポートおよびフォールバック設定 ---
try:
    from bluezero import adapter, device, peripheral
    from gi.repository import GLib

    BLUEZERO_AVAILABLE = True
except Exception:
    BLUEZERO_AVAILABLE = False
    print("[Warning] bluezero or GLib is not available. Running in Mock BLE Mode.")


# =========================================================================
# 設定・定数
# =========================================================================
G = 9.80665
LPF_ALPHA = 0.3

# 危険運転の判定しきい値
THRESHOLD_BRAKE = -0.4
THRESHOLD_ACCEL = 0.3
THRESHOLD_STEER = 0.5
HYSTERESIS = 0.05

# BLE設定
UART_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
RX_CHAR_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
TX_CHAR_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

# データベースファイルパス
DB_PATH = "payload_buffer.db"


# =========================================================================
# ドラレコ (Dashcam) 管理クラス
# =========================================================================
class DashcamManager:
    """
    カメラのリングバッファを管理し、衝撃検知時に前後10秒（計20秒）の動画を保存するクラス。
    """

    def __init__(self):
        self.is_recording_event = False
        self.trigger_time = 0
        self.lock = threading.Lock()

        if PICAMERA_AVAILABLE:
            if PICAMERA_VERSION == 1:
                self.camera = picamera.PiCamera()
                self.camera.resolution = (1280, 720)  # Zero 2Wなら720p 30fpsが安定
                self.camera.framerate = 30
                # 20秒分のリングバッファを用意 (前10秒 + 後10秒のため)
                self.stream = picamera.PiCameraCircularIO(self.camera, seconds=20)
                self.camera.start_recording(self.stream, format="h264")
                print("[Dashcam] (Legacy) カメラ初期化完了。リングバッファ録画を開始しました。")
            elif PICAMERA_VERSION == 2:
                self.camera = Picamera2()
                config = self.camera.create_video_configuration(main={"size": (1280, 720)})
                self.camera.configure(config)
                
                self.encoder = H264Encoder(bitrate=1000000)
                self.stream = CircularOutput(buffersize=600)  # 30fps * 20s = 600 frames
                self.encoder.output = self.stream
                
                self.camera.start()
                self.camera.start_recording(self.encoder, self.stream)
                print("[Dashcam] (Picamera2) カメラ初期化完了。リングバッファ録画を開始しました。")
        else:
            self.camera = None
            self.stream = None
            print("[Dashcam] Mockモードで起動しました。")

    def trigger(self):
        """衝撃検知時に呼び出される。保存の予約を行う。"""
        with self.lock:
            # すでに録画イベント処理中なら無視する（10秒以内の連続検知は1つの動画にまとまるため）
            if not self.is_recording_event:
                self.is_recording_event = True
                self.trigger_time = time.time()
                print("\n[Dashcam] 衝撃検知！10秒後に前後20秒の動画を保存します...")

    def update(self):
        """メインループ内で定期的に呼ばれ、10秒経過したら保存を実行する"""
        with self.lock:
            if self.is_recording_event and (time.time() - self.trigger_time >= 10.0):
                filename = datetime.now().strftime("dashcam_%Y%m%d_%H%M%S.h264")
                print(f"\n[Dashcam] 動画を保存開始: {filename}")

                # センサー処理をブロックしないよう、別スレッドで書き出しを実行
                threading.Thread(
                    target=self._save_video, args=(filename,), daemon=True
                ).start()
                self.is_recording_event = False

    def _save_video(self, filename):
        """ファイルへの書き出し実処理（別スレッド用）"""
        try:
            # 保存先フォルダの名前を定義
            h264_dir = "h264"
            mp4_dir = "mp4"

            # フォルダがなければ自動で作成する
            os.makedirs(h264_dir, exist_ok=True)
            os.makedirs(mp4_dir, exist_ok=True)

            # それぞれのフルパス（フォルダ名 + ファイル名）を作る
            h264_path = os.path.join(h264_dir, filename)
            mp4_path = os.path.join(mp4_dir, filename.replace(".h264", ".mp4"))

            if PICAMERA_AVAILABLE:
                # --- 1. h264/ フォルダに生の .h264 で保存 ---
                if PICAMERA_VERSION == 1:
                    self.stream.copy_to(h264_path, seconds=20)
                elif PICAMERA_VERSION == 2:
                    self.stream.fileoutput = h264_path
                    self.stream.start()
                    time.sleep(1)  # 書き込み完了を少し待つ
                    self.stream.stop()
                print(f"\n[Dashcam] 生動画を保存完了: {h264_path}")

                # --- 2. mp4/ フォルダに変換した .mp4 を保存 ---
                print(f"[Dashcam] 閲覧用MP4を作成中...: {mp4_path}")

                # ffmpegで h264_path のファイルを元に mp4_path へ変換
                cmd = f"ffmpeg -y -r 30 -i {h264_path} -c copy {mp4_path}"
                subprocess.run(
                    cmd,
                    shell=True,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )

                print(f"[Dashcam] ✨ 閲覧用MP4が完成しました: {mp4_path}")
                print(
                    f"[Dashcam] 📁 フォルダ分け完了 -> {h264_dir}/ と {mp4_dir}/"
                )

            else:
                time.sleep(1)  # 保存にかかる時間をモック
                print(
                    f"\n[Dashcam] (Mock) 動画ファイル {h264_path} と {mp4_path} を保存しました。"
                )
        except Exception as e:
            print(f"\n[Dashcam] ❌ 保存エラー: {e}")

    def close(self):
        """終了処理"""
        if PICAMERA_AVAILABLE:
            if PICAMERA_VERSION == 1:
                self.camera.stop_recording()
                self.camera.close()
            elif PICAMERA_VERSION == 2:
                self.camera.stop_recording()
                self.camera.stop()
                self.camera.close()


# =========================================================================
# SQLite データベースバッファクラス (変更なし)
# =========================================================================
class SQLitePayloadBuffer:
    def __init__(self, db_path=DB_PATH):
        self.db_path = db_path
        self.lock = threading.Lock()
        self.has_pending = False
        self._init_db()
        self._update_has_pending()

    def _init_db(self):
        with self.lock, sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("""
                CREATE TABLE IF NOT EXISTS payloads (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp TEXT NOT NULL,
                    data TEXT NOT NULL
                )
            """)
            conn.commit()

    def _update_has_pending(self):
        with self.lock, sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT COUNT(*) FROM payloads")
            count = cursor.fetchone()[0]
            self.has_pending = count > 0

    def push_payload(self, timestamp: str, payload_json: str):
        with self.lock, sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute(
                "INSERT INTO payloads (timestamp, data) VALUES (?, ?)",
                (timestamp, payload_json),
            )
            conn.commit()
            self.has_pending = True

    def pop_pending_payloads(self):
        with self.lock, sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT id, timestamp, data FROM payloads ORDER BY id ASC")
            return cursor.fetchall()

    def remove_payload(self, payload_id: int):
        with self.lock, sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("DELETE FROM payloads WHERE id = ?", (payload_id,))
            conn.commit()
            cursor.execute("SELECT COUNT(*) FROM payloads")
            self.has_pending = cursor.fetchone()[0] > 0


# =========================================================================
# BLE ペリフェラル管理クラス (変更なし)
# =========================================================================
class BLETransmitter:
    # (既存のままなので省略せずそのまま記載します)
    def __init__(self, buffer: SQLitePayloadBuffer):
        self.buffer = buffer
        self.peripheral_device = None
        self.tx_obj = None
        self.is_ready = False
        self.sending_lock = threading.Lock()
        self.sending_in_progress = False

    def on_connect(self, ble_device):
        print(f"\n[BLE] デバイスが接続されました: {ble_device.address}")

    def on_disconnect(self, adapter_address, device_address):
        print(f"\n[BLE] デバイスが切断されました: {device_address}")
        self.is_ready = False
        self.tx_obj = None

    def rx_write_callback(self, value, options):
        try:
            received_str = bytes(value).decode("utf-8").strip()
            if received_str == "READY":
                self.is_ready = True
                threading.Thread(target=self.send_pending_payloads, daemon=True).start()
        except Exception as e:
            print(f"[BLE] RXコールバック例外: {e}")

    def tx_notify_callback(self, notifying, characteristic):
        if notifying:
            self.tx_obj = characteristic
        else:
            self.tx_obj = None
            self.is_ready = False

    def send_pending_payloads(self):
        if not self.is_ready or not self.tx_obj:
            return
        with self.sending_lock:
            if self.sending_in_progress:
                return
            self.sending_in_progress = True

        try:
            while self.is_ready and self.tx_obj:
                payloads = self.buffer.pop_pending_payloads()
                if not payloads:
                    break
                for payload_id, timestamp, payload_json in payloads:
                    if not self.is_ready or not self.tx_obj:
                        break
                    payload_bytes = list(payload_json.encode("utf-8"))
                    try:
                        self.tx_obj.set_value(payload_bytes)
                        self.buffer.remove_payload(payload_id)
                        time.sleep(0.1)
                    except Exception:
                        self.is_ready = False
                        self.tx_obj = None
                        break
                break
        finally:
            with self.sending_lock:
                self.sending_in_progress = False

    def trigger_transmission(self):
        if self.is_ready and self.tx_obj:
            threading.Thread(target=self.send_pending_payloads, daemon=True).start()

    def start_peripheral(self):
        if not BLUEZERO_AVAILABLE:
            while True:
                time.sleep(1)

        adapters = list(adapter.Adapter.available())
        if not adapters:
            sys.exit(1)
        adapter_address = adapters[0].address

        self.peripheral_device = peripheral.Peripheral(
            adapter_address, local_name="omnibus185", appearance=0x0000
        )
        self.peripheral_device.on_connect = self.on_connect
        self.peripheral_device.on_disconnect = self.on_disconnect
        self.peripheral_device.add_service(
            srv_id=1, uuid=UART_SERVICE_UUID, primary=True
        )
        self.peripheral_device.add_characteristic(
            srv_id=1,
            chr_id=1,
            uuid=RX_CHAR_UUID,
            value=[],
            notifying=False,
            flags=["write", "write-without-response"],
            write_callback=self.rx_write_callback,
            read_callback=None,
            notify_callback=None,
        )
        self.peripheral_device.add_characteristic(
            srv_id=1,
            chr_id=2,
            uuid=TX_CHAR_UUID,
            value=[],
            notifying=False,
            flags=["notify"],
            write_callback=None,
            read_callback=None,
            notify_callback=self.tx_notify_callback,
        )
        self.peripheral_device.publish()

        try:
            loop = GLib.MainLoop()
            loop.run()
        except KeyboardInterrupt:
            self.peripheral_device.disconnect()


# =========================================================================
# センサー計測・危険検知メインループ (10Hz)
# =========================================================================
def sensor_and_danger_loop(
    buffer: SQLitePayloadBuffer,
    ble_transmitter: BLETransmitter,
    dashcam: DashcamManager,
):
    """
    ※引数に dashcam を追加
    """
    global MPU6050_AVAILABLE
    offset_x, offset_y, offset_z = 0.0, 0.0, 0.0
    if MPU6050_AVAILABLE:
        try:
            sensor = mpu6050(0x68)
            ax_sum, ay_sum, az_sum = 0, 0, 0
            samples = 50
            for _ in range(samples):
                data = sensor.get_accel_data()
                ax_sum += data["x"] / G
                ay_sum += data["y"] / G
                az_sum += data["z"] / G
                time.sleep(0.02)
            offset_x = ax_sum / samples
            offset_y = ay_sum / samples
            offset_z = (az_sum / samples) - 1.0
        except Exception as e:
            MPU6050_AVAILABLE = False

    filtered_ax, filtered_ay, filtered_az = 0.0, 0.0, 0.0
    is_braking_now, is_accelerating_now, is_steering_now = False, False, False

    while True:
        try:
            time.sleep(0.1)

            if MPU6050_AVAILABLE:
                try:
                    accel_data = sensor.get_accel_data()
                    raw_ax = (accel_data["x"] / G) - offset_x
                    raw_ay = (accel_data["y"] / G) - offset_y
                    raw_az = (accel_data["z"] / G) - offset_z
                except Exception:
                    continue
            else:
                t = time.time()
                raw_ax, raw_ay, raw_az = (
                    0.1 * math.sin(t * 0.5),
                    0.15 * math.cos(t * 0.3),
                    1.0 + 0.05 * math.sin(t),
                )
                cycle = int(t) % 30
                if cycle == 0:
                    raw_ax = -0.55
                elif cycle == 10:
                    raw_ax = 0.45
                elif cycle == 20:
                    raw_ay = 0.7

            filtered_ax = (LPF_ALPHA * raw_ax) + ((1 - LPF_ALPHA) * filtered_ax)
            filtered_ay = (LPF_ALPHA * raw_ay) + ((1 - LPF_ALPHA) * filtered_ay)
            filtered_az = (LPF_ALPHA * raw_az) + ((1 - LPF_ALPHA) * filtered_az)

            print(
                f"\r[G-Meter] X:{filtered_ax:>5.2f} G, Y:{filtered_ay:>5.2f} G, Z:{filtered_az:>5.2f} G",
                end="",
            )

            s_braked, s_accelerated, s_steered = False, False, False

            if not is_braking_now:
                if filtered_ax < THRESHOLD_BRAKE:
                    is_braking_now, s_braked = True, True
            else:
                if filtered_ax > (THRESHOLD_BRAKE + HYSTERESIS):
                    is_braking_now = False

            if not is_accelerating_now:
                if filtered_ax > THRESHOLD_ACCEL:
                    is_accelerating_now, s_accelerated = True, True
            else:
                if filtered_ax < (THRESHOLD_ACCEL - HYSTERESIS):
                    is_accelerating_now = False

            if not is_steering_now:
                if abs(filtered_ay) > THRESHOLD_STEER:
                    is_steering_now, s_steered = True, True
            else:
                if abs(filtered_ay) < (THRESHOLD_STEER - HYSTERESIS):
                    is_steering_now = False

            has_new_danger = any([s_braked, s_accelerated, s_steered])
            if has_new_danger:
                # ========== 追加箇所 ==========
                dashcam.trigger()
                # ==============================

                timestamp = datetime.now().strftime("%Y/%m/%d-%H:%M:%S")
                payload = {
                    "timestamp": timestamp,
                    "x": round(filtered_ax, 4),
                    "y": round(filtered_ay, 4),
                    "z": round(filtered_az, 4),
                }
                if s_braked:
                    payload["s_braked"] = True
                if s_accelerated:
                    payload["s_accelerated"] = True
                if s_steered:
                    payload["s_steered"] = True

                payload["date"] = datetime.now().strftime("%y/%m/%d-%H:%M:%S")
                payload["latitude"] = 35.6812
                payload["longitude"] = 139.7671

                payload_json = json.dumps(payload) + "\r\n"

                if ble_transmitter.is_ready and not buffer.has_pending:
                    try:
                        ble_transmitter.tx_obj.set_value(
                            list(payload_json.encode("utf-8"))
                        )
                    except Exception:
                        ble_transmitter.is_ready = False
                        ble_transmitter.tx_obj = None
                        buffer.push_payload(timestamp, payload_json)
                else:
                    buffer.push_payload(timestamp, payload_json)
                    if ble_transmitter.is_ready:
                        ble_transmitter.trigger_transmission()

            # ========== 追加箇所 ==========
            # ループの最後でドラレコのタイマー状態をチェック
            dashcam.update()
            # ==============================

        except Exception as e:
            print(f"\n[MainLoop] 予期せぬエラー: {e}")


# =========================================================================
# メインエントリーポイント
# =========================================================================
def main():
    print("====================================================")
    print(" G-Meter & BLE Transmitter with Dashcam Buffer")
    print("====================================================")

    try:
        print("[BLE] アドバタイズ間隔を100msに最適化中...")
        # 100ms (16進数で 0x00A0 -> コマンド用に A0 00)
        cmd = "sudo hciconfig hci0 noleadv && sudo hcitool -i hci0 cmd 0x08 0x0006 0xA0 0x00 0xA0 0x00 0x07 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x07 0x00"
        subprocess.run(cmd, shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception as e:
        print(f"[Warning] BLE間隔の調整に失敗しました: {e}")

    # マネージャー初期化
    buffer = SQLitePayloadBuffer(DB_PATH)
    ble_transmitter = BLETransmitter(buffer)
    dashcam = DashcamManager()  # <--- 追加

    sensor_thread = threading.Thread(
        target=sensor_and_danger_loop,
        args=(buffer, ble_transmitter, dashcam),  # <--- 引数に追加
        daemon=True,
    )
    sensor_thread.start()

    try:
        ble_transmitter.start_peripheral()
    except KeyboardInterrupt:
        print("\nユーザーによる強制終了を受け取りました。")
    finally:
        dashcam.close()  # <--- 追加
        print("プログラムを終了しました。")


if __name__ == "__main__":
    main()
 
