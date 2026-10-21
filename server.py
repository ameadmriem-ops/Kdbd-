import os
import sys
import mimetypes
from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.parse

PORT = 3000
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
APK_PATH = os.path.join(BASE_DIR, "hamza", "app.apk")
ALT_APK_PATH = os.path.join(BASE_DIR, "app", "build", "outputs", "apk", "debug", "app-debug.apk")

def get_valid_apk_path():
    if os.path.exists(APK_PATH) and os.path.getsize(APK_PATH) > 0:
        return APK_PATH
    if os.path.exists(ALT_APK_PATH) and os.path.getsize(ALT_APK_PATH) > 0:
        return ALT_APK_PATH
    return None

class ApkDownloadHandler(BaseHTTPRequestHandler):
    def send_cors_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, HEAD, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', '*')

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_cors_headers()
        self.end_headers()

    def do_HEAD(self):
        parsed_url = urllib.parse.urlparse(self.path)
        path = parsed_url.path.strip('/')
        
        apk_target = get_valid_apk_path()
        if path in ['hamza/app.apk', 'app.apk', 'download', 'app-debug.apk', 'hamza']:
            if apk_target:
                file_size = os.path.getsize(apk_target)
                self.send_response(200)
                self.send_header('Content-Type', 'application/vnd.android.package-archive')
                self.send_header('Content-Disposition', 'attachment; filename="app.apk"')
                self.send_header('Content-Length', str(file_size))
                self.send_header('Accept-Ranges', 'bytes')
                self.send_header('Cache-Control', 'public, max-age=3600')
                self.send_cors_headers()
                self.end_headers()
                return
            else:
                self.send_error(404, "APK file not found")
                return
        
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_cors_headers()
        self.end_headers()

    def do_GET(self):
        parsed_url = urllib.parse.urlparse(self.path)
        path = parsed_url.path.strip('/')
        
        apk_target = get_valid_apk_path()

        # Handle APK download requests
        if path in ['hamza/app.apk', 'app.apk', 'download', 'app-debug.apk', 'hamza']:
            if not apk_target:
                self.send_error(404, "APK not found. Please build the project first.")
                return

            file_size = os.path.getsize(apk_target)
            range_header = self.headers.get('Range')

            start = 0
            end = file_size - 1

            if range_header and range_header.startswith('bytes='):
                range_parts = range_header[6:].split('-')
                if range_parts[0]:
                    start = int(range_parts[0])
                if len(range_parts) > 1 and range_parts[1]:
                    end = int(range_parts[1])

                if start >= file_size or end >= file_size or start > end:
                    self.send_response(416)
                    self.send_header('Content-Range', f'bytes */{file_size}')
                    self.end_headers()
                    return

                length = end - start + 1
                self.send_response(206)
                self.send_header('Content-Range', f'bytes {start}-{end}/{file_size}')
            else:
                length = file_size
                self.send_response(200)

            self.send_header('Content-Type', 'application/vnd.android.package-archive')
            self.send_header('Content-Disposition', 'attachment; filename="app.apk"')
            self.send_header('Content-Length', str(length))
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('Cache-Control', 'public, max-age=3600')
            self.send_cors_headers()
            self.end_headers()

            try:
                with open(apk_target, 'rb') as f:
                    f.seek(start)
                    remaining = length
                    chunk_size = 1024 * 1024  # 1MB chunks
                    while remaining > 0:
                        read_bytes = min(remaining, chunk_size)
                        chunk = f.read(read_bytes)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        remaining -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass
            return

        # Serve landing page for any other route
        apk_exists = apk_target is not None
        file_size_mb = f"{os.path.getsize(apk_target) / (1024*1024):.1f}" if apk_exists else "0"
        
        html = f"""<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>تحميل تطبيق BLACK ANIME</title>
    <style>
        * {{ box-sizing: border-box; margin: 0; padding: 0; }}
        body {{
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Cairo", sans-serif;
            background: linear-gradient(135deg, #0d0e15 0%, #171926 100%);
            color: #ffffff;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 20px;
        }}
        .card {{
            background: rgba(26, 29, 45, 0.95);
            border: 1px solid rgba(255, 255, 255, 0.1);
            border-radius: 24px;
            box-shadow: 0 20px 50px rgba(0, 0, 0, 0.6);
            padding: 32px 24px;
            width: 100%;
            max-width: 440px;
            text-align: center;
            backdrop-filter: blur(10px);
        }}
        .icon-container {{
            width: 96px;
            height: 96px;
            margin: 0 auto 20px;
            background: #111;
            border-radius: 22px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 48px;
            box-shadow: 0 10px 25px rgba(124, 77, 255, 0.3);
            border: 2px solid #7c4dff;
        }}
        h1 {{
            font-size: 26px;
            font-weight: 800;
            margin-bottom: 8px;
            background: linear-gradient(90deg, #ffffff, #b388ff);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
        }}
        .badge {{
            display: inline-block;
            background: rgba(16, 185, 129, 0.15);
            color: #10b981;
            border: 1px solid rgba(16, 185, 129, 0.3);
            padding: 6px 14px;
            border-radius: 20px;
            font-size: 13px;
            font-weight: 600;
            margin-bottom: 24px;
        }}
        .info-grid {{
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 12px;
            margin-bottom: 28px;
            background: rgba(0, 0, 0, 0.25);
            padding: 16px;
            border-radius: 16px;
            text-align: right;
        }}
        .info-item {{ font-size: 13px; color: #9ca3af; }}
        .info-item b {{ color: #ffffff; display: block; font-size: 15px; margin-top: 2px; }}
        .download-btn {{
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 10px;
            background: linear-gradient(90deg, #7c4dff, #6200ea);
            color: #ffffff;
            text-decoration: none;
            padding: 18px 24px;
            border-radius: 16px;
            font-size: 17px;
            font-weight: 700;
            box-shadow: 0 10px 30px rgba(124, 77, 255, 0.4);
            transition: transform 0.2s, box-shadow 0.2s;
            cursor: pointer;
            border: none;
            width: 100%;
        }}
        .download-btn:hover {{
            transform: translateY(-2px);
            box-shadow: 0 15px 35px rgba(124, 77, 255, 0.6);
        }}
        .download-btn:active {{
            transform: translateY(1px);
        }}
        .steps {{
            margin-top: 24px;
            font-size: 12px;
            color: #6b7280;
            line-height: 1.6;
            text-align: right;
            border-top: 1px solid rgba(255, 255, 255, 0.05);
            padding-top: 16px;
        }}
        .steps ol {{ padding-right: 20px; }}
    </style>
</head>
<body>
    <div class="card">
        <div class="icon-container">📺</div>
        <h1>BLACK ANIME</h1>
        <div class="badge">جاهز للتثبيت والتنزيل المباشر</div>
        
        <div class="info-grid">
            <div class="info-item">حجم الملف: <b>{file_size_mb} ميجابايت</b></div>
            <div class="info-item">الإصدار: <b>1.0 (رسمي)</b></div>
            <div class="info-item">نوع الحزمة: <b>Android APK</b></div>
            <div class="info-item">الحالة: <b>بناء ناجح ونظيف</b></div>
        </div>

        <a href="/hamza/app.apk" class="download-btn" id="dlBtn" download="app.apk">
            <span>تنزيل ملف APK المباشر</span>
            <span>⬇️</span>
        </a>

        <div class="steps">
            <b>تعليمات التثبيت على الهاتف:</b>
            <ol>
                <li>اضغط على زر التنزيل أعلاه لحفظ ملف <code>app.apk</code>.</li>
                <li>افتح الملف بعد اكتمال التنزيل واضغط "تثبيت".</li>
                <li>إذا طُلب منك، فعّل خيار "السماح بالتثبيت من هذا المصدر".</li>
            </ol>
        </div>
    </div>
</body>
</html>"""
        encoded = html.encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(encoded)))
        self.send_cors_headers()
        self.end_headers()
        self.wfile.write(encoded)

def run():
    print(f"Starting APK download server on port {PORT}...")
    server = HTTPServer(('0.0.0.0', PORT), ApkDownloadHandler)
    server.serve_forever()

if __name__ == '__main__':
    run()
