"""Project guard against the oversized coroutine shape seen in the 0.3.8 crash.

These conservative limits are project checks, not DEX specification limits.
Uses only the Python standard library; never reads vehicle/user data.
"""
import struct,sys,zipfile,json

PROTOCOL_PREFIXES = tuple('Lcom/vagdiagnostic/app/' + name for name in (
    'transport/ElmVagScan', 'transport/ElmTp20Channel',
    'transport/ElmUdsRawTransaction', 'transport/ElmUdsPending',
    'transport/VagReadRetry', 'core/VagIsoTpCollection', 'core/VagIsoTpOutgoing',
    'transport/ElmEngine', 'transport/ElmSettingCache', 'transport/ElmNativeSegmentation',
    'transport/ElmAdapterNativeProbe', 'transport/ElmUdsCanConfiguration',
    'transport/ElmUdsNativeDriver', 'core/VagUdsNativeTransaction'))

def inspect(data):
    def u32(at):return struct.unpack_from('<I',data,at)[0]
    def uleb(at):
        n=shift=0
        while True:
            byte=data[at];at+=1;n|=(byte&127)<<shift
            if byte<128:return n,at
            shift+=7
            if shift>28:raise ValueError('Invalid DEX ULEB128')
    strings=[]
    for i in range(u32(0x38)):
        at=u32(u32(0x3c)+i*4);_,at=uleb(at)
        strings.append(data[at:data.index(b'\0',at)].decode('utf-8',errors='replace'))
    types=[strings[u32(u32(0x44)+i*4)] for i in range(u32(0x40))]
    method_off=u32(0x5c)
    result=[]
    for i in range(u32(0x60)):
        at=u32(0x64)+i*32;name=types[u32(at)]
        if not name.startswith(PROTOCOL_PREFIXES):continue
        at=u32(at+24)
        if not at:continue
        counts=[]
        for _ in range(4):value,at=uleb(at);counts.append(value)
        for _ in range(counts[0]+counts[1]):_,at=uleb(at);_,at=uleb(at)
        for count in counts[2:]:
            idx=0
            for _ in range(count):
                diff,at=uleb(at);idx+=diff;_,at=uleb(at);code,at=uleb(at)
                if not code:continue
                registers=struct.unpack_from('<H',data,code)[0];units=u32(code+12)
                method=strings[u32(method_off+idx*8+4)]
                result.append(dict(owner=name,method=method,registers=registers,code_units=units))
    return result

if __name__=='__main__':
    rows=[]
    with zipfile.ZipFile(sys.argv[1]) as archive:
        for name in archive.namelist():
            if name.endswith('.dex'):rows.extend(inspect(archive.read(name)))
    if not rows:raise SystemExit('No scanner methods found in APK')
    bad=[r for r in rows if r['registers']>256 or r['code_units']>32768]
    print(json.dumps(dict(methods=len(rows),max_registers=max(r['registers'] for r in rows),
        max_code_units=max(r['code_units'] for r in rows),oversized=bad)))
    if bad:raise SystemExit(1)
