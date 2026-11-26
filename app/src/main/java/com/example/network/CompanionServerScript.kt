package com.example.network

object CompanionServerScript {
    val pythonScript: String = """
#!/usr/bin/env python3
# LAN Print Companion Server (Windows / Linux / macOS)
# Instalasi:
#   pip install fastapi uvicorn python-multipart zeroconf
# Opsional Windows: pasang SumatraPDF.exe untuk cetak PDF senyap
# Opsional Office: pasang LibreOffice (soffice) untuk konversi DOCX/XLSX/PPTX

import json, os, platform, secrets, shutil, subprocess, tempfile, uuid
from pathlib import Path
from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import JSONResponse
from zeroconf import ServiceInfo, Zeroconf
import socket

APP_PORT = 8765
PAIR_PIN = os.environ.get("LANPRINT_PIN", "123456")
TOKENS = set()
JOBS = {}

app = FastAPI(title="LAN Print Companion Server")

def list_os_printers():
    system = platform.system()
    printers = []
    if system == "Windows":
        out = subprocess.check_output(
            ["powershell", "-Command", "Get-Printer | Select-Object -ExpandProperty Name"],
            text=True, errors="ignore"
        )
        for idx, name in enumerate(x.strip() for x in out.splitlines() if x.strip()):
            printers.append({"name": name, "displayName": name, "supportsColor": True, "supportsDuplex": True, "isDefault": idx == 0})
    else:
        out = subprocess.check_output(["lpstat", "-p"], text=True, errors="ignore")
        for idx, line in enumerate(out.splitlines()):
            if line.startswith("printer "):
                name = line.split()[1]
                printers.append({"name": name, "displayName": name, "supportsColor": True, "supportsDuplex": True, "isDefault": idx == 0})
    return printers

@app.post("/api/v1/pair")
def pair(payload: dict):
    if str(payload.get("pin", "")).strip() != PAIR_PIN:
        raise HTTPException(status_code=401, detail="PIN salah")
    token = secrets.token_hex(24)
    TOKENS.add(token)
    return {"token": token, "serverName": socket.gethostname()}

@app.get("/api/v1/printers")
def get_printers(authorization: str = Header(default="")):
    if PAIR_PIN and authorization.replace("Bearer ", "") not in TOKENS:
        raise HTTPException(status_code=401, detail="Memerlukan PIN Pairing")
    return {"printers": list_os_printers()}

@app.post("/api/v1/jobs")
async def create_job(
    printerName: str = Form(...),
    options: str = Form("{}"),
    file: UploadFile = File(...),
    authorization: str = Header(default="")
):
    if PAIR_PIN and authorization.replace("Bearer ", "") not in TOKENS:
        raise HTTPException(status_code=401, detail="Token tidak valid")

    job_id = str(uuid.uuid4())[:8]
    tmp_dir = Path(tempfile.mkdtemp(prefix="lanprint_"))
    in_path = tmp_dir / (file.filename or "doc.pdf")
    in_path.write_bytes(await file.read())
    JOBS[job_id] = {"jobId": job_id, "status": "printing", "message": "Memproses cetak..."}

    pdf_path = in_path
    if in_path.suffix.lower() in {".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".odt", ".rtf"}:
        JOBS[job_id]["status"] = "converting"
        subprocess.run(["soffice", "--headless", "--convert-to", "pdf", "--outdir", str(tmp_dir), str(in_path)], check=True)
        pdf_path = in_path.with_suffix(".pdf")

    opts = json.loads(options)
    copies = int(opts.get("copies", 1))
    system = platform.system()
    if system == "Windows":
        sumatra = shutil.which("SumatraPDF.exe") or r"C:\Program Files\SumatraPDF\SumatraPDF.exe"
        subprocess.Popen([sumatra, "-print-to", printerName, "-print-settings", f"{copies}x", str(pdf_path)])
    else:
        subprocess.run(["lp", "-d", printerName, "-n", str(copies), str(pdf_path)], check=True)

    JOBS[job_id] = {"jobId": job_id, "status": "done", "message": f"Dikirim ke {printerName}"}
    return JOBS[job_id]

@app.get("/api/v1/jobs/{job_id}")
def job_status(job_id: str):
    return JOBS.get(job_id, {"jobId": job_id, "status": "done", "message": "Selesai"})

@app.delete("/api/v1/jobs/{job_id}")
def cancel_job(job_id: str):
    JOBS[job_id] = {"jobId": job_id, "status": "cancelled", "message": "Dibatalkan"}
    return {"cancelled": True}

if __name__ == "__main__":
    import uvicorn
    print(f"PIN Pairing LAN Print: {PAIR_PIN}")
    ip = socket.gethostbyname(socket.gethostname())
    zc = Zeroconf()
    info = ServiceInfo("_lanprint._tcp.local.", f"{socket.gethostname()}._lanprint._tcp.local.",
                       addresses=[socket.inet_aton(ip)], port=APP_PORT)
    zc.register_service(info)
    try:
        uvicorn.run(app, host="0.0.0.0", port=APP_PORT)
    finally:
        zc.unregister_service(info)
        zc.close()
    """.trimIndent()
}
