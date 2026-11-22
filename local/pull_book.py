"""Explicit server-to-local book import for CLI testing."""
import argparse
import json
from pathlib import Path

from .launcher import DEFAULT_DOWNLOADS,Transport
from .library import LocalLibrary
from .server_library import ServerLibrary


def main():
    parser=argparse.ArgumentParser(description="从合成服务器拉取已有原文到本地书库")
    parser.add_argument("--host",default="jetson@192.168.3.33")
    parser.add_argument("--tunnel-port",type=int,default=17860)
    parser.add_argument("--remote-port",type=int,default=17860)
    parser.add_argument("--remote-state",default="/home/jetson/.local/share/shanye-tts")
    parser.add_argument("--token-file",type=Path)
    parser.add_argument("--direct",action="store_true")
    parser.add_argument("--output",type=Path,default=DEFAULT_DOWNLOADS)
    parser.add_argument("--book-id",help="省略时列出服务器书籍")
    parser.add_argument("--chapters",default="",help="例如 984,986-988；省略时拉取服务器保存的全部章节")
    args=parser.parse_args()
    transport=Transport(args.host,args.tunnel_port,args.remote_port,args.remote_state,args.token_file,args.direct)
    try:
        transport.start()
        remote=ServerLibrary(transport)
        if args.book_id:
            path=LocalLibrary.initialize_downloads(args.output)
            result=remote.pull(LocalLibrary(path),args.book_id,args.chapters)
            result["path"]=str(path)
        else:
            result=remote.books()
        print(json.dumps(result,ensure_ascii=False,indent=2))
    finally:
        transport.close()


if __name__=="__main__":
    main()
