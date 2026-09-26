"""Add the XunYi location only to the existing api.qianban.cloud TLS server."""
from pathlib import Path
from datetime import datetime

config = Path("/www/server/panel/vhost/nginx/qianban-platform.conf")
marker = "    include /etc/xunyi/nginx-location.conf;\n"
content = config.read_text()
if marker in content:
    print("location already installed")
else:
    anchor = content.find("server_name api.qianban.cloud;")
    if anchor < 0:
        raise SystemExit("api.qianban.cloud server block not found")
    insertion = content.find("    location / {", anchor)
    if insertion < 0:
        raise SystemExit("api.qianban.cloud root location not found")
    backup = config.with_name(config.name + ".before-xunyi-" + datetime.now().strftime("%Y%m%d%H%M%S"))
    backup.write_text(content)
    config.write_text(content[:insertion] + marker + "\n" + content[insertion:])
    print("backup:", backup)
