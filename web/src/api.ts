let session = "";
let bootstrap: Promise<void> | undefined;
export async function api<T = any>(
  path: string,
  body?: unknown,
  key?: string,
): Promise<T> {
  if (!bootstrap)
    bootstrap = fetch("/session")
      .then((r) => {
        if (!r.ok) throw Error("请用本地启动器打开工作台");
        return r.json();
      })
      .then((v) => {
        session = v.session;
      })
      .catch((e) => {
        bootstrap = undefined;
        throw e;
      });
  await bootstrap;
  const response = await fetch(path, {
    method: body === undefined ? "GET" : "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Local-Session": session,
      ...(key ? { "Idempotency-Key": key } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await response.json();
  if (!response.ok) {
    const error = new Error(
      data.error?.message ||
        JSON.stringify(data.detail) ||
        `请求失败 ${response.status}`,
    ) as Error & { status?: number };
    error.status = response.status;
    throw error;
  }
  return data;
}
export type Pending = { path: string; body: unknown; key: string };
export function pending(): Pending | null {
  try {
    return JSON.parse(localStorage.getItem("shanye-pending") || "null");
  } catch {
    return null;
  }
}
export async function mutate(path: string, body: unknown) {
  let record = pending();
  if (
    record &&
    (record.path !== path ||
      JSON.stringify(record.body) !== JSON.stringify(body))
  )
    throw Error("有一笔提交尚未确认，请先点击「核对提交」");
  record ||= { path, body, key: crypto.randomUUID() };
  localStorage.setItem("shanye-pending", JSON.stringify(record));
  try {
    const result = await api(record.path, record.body, record.key);
    localStorage.removeItem("shanye-pending");
    return result;
  } catch (e) {
    const status = (e as { status?: number }).status;
    if (status && status >= 400 && status < 500)
      localStorage.removeItem("shanye-pending");
    throw e;
  }
}

export async function uploadReference(
  file: File,
  refText: string,
) {
  if (!bootstrap)
    bootstrap = fetch("/session")
      .then((r) => r.json())
      .then((v) => {
        session = v.session;
      });
  await bootstrap;
  const form = new FormData();
  form.append("reference", file);
  form.append("ref_text", refText);
  const response = await fetch("/api/v1/voice-clones", {
    method: "POST",
    body: form,
    headers: { "X-Local-Session": session },
  });
  const data = await response.json();
  if (!response.ok)
    throw Error(data.error?.message || `上传失败 ${response.status}`);
  return data;
}
