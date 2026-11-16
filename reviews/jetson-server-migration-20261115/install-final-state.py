"""Install consistent source snapshots while all target units are stopped."""
from pathlib import Path
import shutil
import sqlite3
src=Path('/home/jetson/workspace/migration-20261115/final-snapshot')
state=Path('/home/jetson/.local/share/shanye-tts')
targets={'service.sqlite':state/'service.sqlite','relay-results.sqlite':state/'relay-results.sqlite','content.db':Path('/home/jetson/workspace/shanye-content/content.db')}
for name,path in targets.items():
    assert (src/name).is_file()
    for suffix in ('-wal','-shm'):
        path.with_name(path.name+suffix).unlink(missing_ok=True)
    shutil.copyfile(src/name,path);path.chmod(0o600)
    db=sqlite3.connect(path)
    if name=='service.sqlite':
        db.execute('delete from runtime')
    replacements=[('/h/work/shanye-tts-service','/home/jetson/workspace/shanye-tts-service'),('/h/work/shanye-content','/home/jetson/workspace/shanye-content')]
    tables=([('results','path')] if name=='relay-results.sqlite' else [('content','path'),('results','path')] if name=='content.db' else [])
    for table,column in tables:
        for old,new in replacements:
            db.execute(f'update {table} set {column}=? || substr({column},?) where {column} like ?', (new,len(old)+1,old+'/%'))
    db.commit()
    assert db.execute('pragma integrity_check').fetchone()[0]=='ok'
    db.close()
print('Final databases installed, paths rewritten, all integrity checks passed.')
