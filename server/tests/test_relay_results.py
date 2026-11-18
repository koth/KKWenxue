from contextlib import closing
import tempfile
import time
from pathlib import Path
import unittest
from urllib.request import Request, urlopen
from urllib.error import HTTPError

from server.config import Settings
from server.relay_results import ResultStore, ResultServer


class LanResultsTests(unittest.TestCase):
    def test_lan_download_scopes_credentials_checks_expiry_and_survives_restart(self):
        with tempfile.TemporaryDirectory() as root:
            settings = Settings(state=Path(root)/"state", audio=Path(root)/"audio")
            store = ResultStore(settings)
            path = settings.audio/"chapter.zip"
            path.write_bytes(b"audiobook test package")
            identifier = "11111111-1111-4111-8111-111111111111"
            result = store.publish(identifier, path, time.time()+100, "http://127.0.0.1:17871")
            restarted = ResultStore(settings)
            self.assertEqual(result["direct"]["token"], restarted.get(identifier)["token"])
            server = ResultServer(restarted, "127.0.0.1", 0)
            server.start()
            try:
                url = f"http://127.0.0.1:{server.http.server_port}/v1/results/{identifier}"
                for headers in ({}, {"X-Relay-Token":"private-admin-token"}, {"X-Result-Token":"wrong"}):
                    with self.assertRaises(HTTPError) as error:
                        urlopen(Request(url, headers=headers))
                    self.assertEqual(401, error.exception.code)
                headers = {"X-Result-Token":result["direct"]["token"]}
                with urlopen(Request(url, headers=headers)) as response:
                    self.assertEqual(path.read_bytes(), response.read())
                    self.assertEqual(result["sha256"], response.headers["X-SHA256"])
                    self.assertEqual('bytes',response.headers['Accept-Ranges'])
                etag='"'+result['sha256']+'"'
                with urlopen(Request(url,headers={**headers,'Range':'bytes=5-','If-Range':etag})) as response:
                    self.assertEqual(206,response.status)
                    self.assertEqual(path.read_bytes()[5:],response.read())
                    self.assertEqual(f'bytes 5-{path.stat().st_size-1}/{path.stat().st_size}',response.headers['Content-Range'])
                with urlopen(Request(url,headers={**headers,'Range':'bytes=2-6'})) as response:
                    self.assertEqual(path.read_bytes()[2:7],response.read())
                with urlopen(Request(url,headers={**headers,'Range':'bytes=5-','If-Range':'"other-version"'})) as response:
                    self.assertEqual(200,response.status)
                    self.assertEqual(path.read_bytes(),response.read())
                for value in ['bytes=999-','bytes=7-2','bytes=1-2,4-5','bytes=-5']:
                    with self.assertRaises(HTTPError) as error:
                        urlopen(Request(url,headers={**headers,'Range':value}))
                    self.assertEqual(416,error.exception.code)
                with self.assertRaises(HTTPError):
                    urlopen(Request(url+"/../../chapter.zip", headers=headers))
                with closing(restarted.connect()) as db:
                    db.execute("UPDATE results SET expires=?", (time.time()-1,))
                with self.assertRaises(HTTPError) as error:
                    urlopen(Request(url, headers=headers))
                self.assertEqual(410, error.exception.code)
            finally:
                server.close()
