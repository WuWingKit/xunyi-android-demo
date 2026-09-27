"""Small server-side AMap Web Service client. The key never reaches Android."""
import json
import http.client
import socket
import urllib.parse


class AmapError(RuntimeError):
    pass


class AmapClient:
    BASE = "https://restapi.amap.com"

    def __init__(self, key: str, timeout: float = 6):
        self.key = key
        self.timeout = timeout

    def _get(self, path: str, **params):
        if not self.key:
            raise AmapError("AMAP_KEY is not configured")
        query = urllib.parse.urlencode({"key": self.key, "output": "JSON", **params})
        connection = http.client.HTTPSConnection("restapi.amap.com", timeout=self.timeout)
        # The AMap key is whitelisted for this server's IPv4 address. Its IPv6
        # egress has a different address, so choose IPv4 only for this client.
        connection._create_connection = self._ipv4_connection
        try:
            connection.request("GET", path + "?" + query,
                               headers={"User-Agent": "XunYiDemo/0.2", "Accept": "application/json"})
            response = connection.getresponse()
            if response.status != 200:
                raise AmapError("AMap HTTP response unavailable")
            data = json.loads(response.read(1_000_000))
        except (OSError, TimeoutError, ValueError) as exc:
            raise AmapError("AMap network response unavailable") from exc
        finally:
            connection.close()
        if str(data.get("status")) != "1":
            raise AmapError("AMap rejected request: " + str(data.get("infocode", "unknown")))
        return data

    @staticmethod
    def _ipv4_connection(address, timeout=socket._GLOBAL_DEFAULT_TIMEOUT, source_address=None):
        host, port = address
        last_error = None
        for family, kind, protocol, _, sockaddr in socket.getaddrinfo(host, port, socket.AF_INET, socket.SOCK_STREAM):
            sock = socket.socket(family, kind, protocol)
            try:
                sock.settimeout(timeout)
                if source_address:
                    sock.bind(source_address)
                sock.connect(sockaddr)
                return sock
            except OSError as exc:
                last_error = exc
                sock.close()
        raise last_error or OSError("no IPv4 address available")

    def convert_gps(self, longitude: float, latitude: float):
        data = self._get(
            "/v3/assistant/coordinate/convert",
            locations=f"{longitude:.6f},{latitude:.6f}",
            coordsys="gps",
        )
        try:
            lon, lat = map(float, data["locations"].split(";")[0].split(","))
            return lon, lat
        except (ValueError, KeyError, TypeError) as exc:
            raise AmapError("AMap coordinate conversion result invalid") from exc

    def reverse(self, longitude: float, latitude: float):
        data = self._get(
            "/v3/geocode/regeo",
            location=f"{longitude:.6f},{latitude:.6f}",
            extensions="base",
        )
        return str(data.get("regeocode", {}).get("formatted_address", "")).strip()

    def search(self, keywords: str, region: str = ""):
        params = {"keywords": keywords, "page_size": 5, "page_num": 1}
        if region:
            params["region"] = region
        data = self._get("/v5/place/text", **params)
        result = []
        for poi in data.get("pois", [])[:5]:
            location = poi.get("location")
            if not isinstance(location, str) or "," not in location:
                continue
            try:
                lon, lat = map(float, location.split(","))
            except ValueError:
                continue
            result.append({
                "poiId": str(poi.get("id", "")),
                "name": str(poi.get("name", "")),
                "address": str(poi.get("address", "")) if isinstance(poi.get("address"), str) else "",
                "city": str(poi.get("cityname", "")) if isinstance(poi.get("cityname"), str) else "",
                "district": str(poi.get("adname", "")) if isinstance(poi.get("adname"), str) else "",
                "longitude": lon,
                "latitude": lat,
                "coordinateSystem": "GCJ-02",
            })
        return result

    def static_map(self, points, overview=False):
        """Return a bounded PNG from AMap using only server-selected GCJ-02 points."""
        if not self.key or not 1 <= len(points) <= 10:
            raise AmapError("map location unavailable")
        try:
            coordinates = [(float(lon), float(lat)) for lon, lat in points]
            if not all(70 <= lon <= 140 and 0 <= lat <= 60 for lon, lat in coordinates):
                raise ValueError("coordinates out of range")
        except (TypeError, ValueError) as exc:
            raise AmapError("map coordinates invalid") from exc
        markers = "|".join(f"large,0xA84D32,{chr(65 + i)}:{lon:.6f},{lat:.6f}"
                           for i, (lon, lat) in enumerate(coordinates))
        params = {"key": self.key, "size": "720*720" if overview else "720*480",
                  "scale": "1", "markers": markers}
        if len(points) == 1:
            params["zoom"] = "15"
        query = urllib.parse.urlencode(params)
        connection = http.client.HTTPSConnection("restapi.amap.com", timeout=self.timeout)
        connection._create_connection = self._ipv4_connection
        try:
            connection.request("GET", "/v3/staticmap?" + query,
                               headers={"User-Agent": "XunYiDemo/0.3", "Accept": "image/png"})
            response = connection.getresponse()
            image = response.read(1_500_001)
            if response.status != 200 or not image.startswith(b"\x89PNG\r\n\x1a\n") or len(image) > 1_500_000:
                raise AmapError("map image unavailable")
            return image
        except (OSError, TimeoutError) as exc:
            raise AmapError("map network unavailable") from exc
        finally:
            connection.close()
