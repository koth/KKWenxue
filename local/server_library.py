"""Pull explicit source snapshots through the launcher's authenticated tunnel."""
from concurrent.futures import ThreadPoolExecutor
import http.client
import json
from urllib.parse import quote


class ServerLibrary:
    def __init__(self, transport):
        self.transport=transport

    def request(self,path,body=None):
        connection=http.client.HTTPConnection("127.0.0.1",self.transport.port,timeout=30)
        try:
            connection.request("GET" if body is None else "POST",path,
                body=None if body is None else json.dumps(body).encode(),
                headers={"X-Service-Token":self.transport.token,"Content-Type":"application/json"})
            response=connection.getresponse()
            raw=response.read(64*1024*1024+1)
            if len(raw)>64*1024*1024:
                raise ValueError("服务器数据过大，请缩小拉取范围")
            value=json.loads(raw)
            if response.status not in (200,201):
                raise ValueError(value.get("error",{}).get("message","服务器书库暂时不可用"))
            return value
        except (OSError,http.client.HTTPException) as error:
            raise ConnectionError("无法连接服务器书库，请检查服务器连接后重试") from error
        finally:
            connection.close()

    def books(self):
        return self.request("/api/v1/books")

    def pull(self,library,book_id,expression=""):
        book=next((b for b in self.books()["items"] if b["id"]==book_id),None)
        if not book:
            raise ValueError("服务器没有这本书")
        if expression.strip():
            selected=self.request("/api/v1/chapter-selections/resolve",{"book_id":book_id,"expression":expression})
            if selected.get("ambiguous"):
                raise ValueError("章号对应多个章节，请填写包含该章的范围，例如 984-984")
            chapters=selected["chapters"]
        else:
            if book["chapters"]>2000:
                raise ValueError("本书超过 2000 章，请填写测试所需的章号范围")
            chapters=[]
            total=None
            while total is None or len(chapters)<total:
                page=self.request(f"/api/v1/books/{quote(book_id,safe='')}/chapters?offset={len(chapters)}&limit=500")
                if total is not None and page["total"]!=total:
                    raise ValueError("服务器目录已变化，请重新拉取")
                total=page["total"]
                if not page["items"] or total>2000:
                    raise ValueError("服务器目录为空或过大，请缩小拉取范围")
                chapters.extend(page["items"])
        if not chapters or len(chapters)>2000 or len({c["id"] for c in chapters})!=len(chapters):
            raise ValueError("拉取章节为空、重复或过多")
        def load(chapter):
            revision=self.request("/api/v1/revisions/"+quote(chapter["current_revision"],safe=''))
            if revision["id"]!=chapter["current_revision"] or revision["chapter_id"]!=chapter["id"]:
                raise ValueError("服务器原文版本不匹配，请重新拉取")
            return {**chapter,"canonical_text":revision["canonical_text"],"source_sha256":revision["source_sha256"]}
        loaded=[]
        size=0
        with ThreadPoolExecutor(max_workers=4) as pool:
            for start in range(0,len(chapters),4):
                for item in pool.map(load,chapters[start:start+4]):
                    size+=len(item["canonical_text"].encode())
                    if size>64*1024*1024:
                        raise ValueError("拉取原文超过 64 MB，请缩小范围")
                    loaded.append(item)
        return library.import_chapters(book,loaded)
