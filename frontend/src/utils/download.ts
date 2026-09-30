/**
 * 统一导出入口：使用浏览器下载 JSON/CSV，临时对象地址用完释放。
 */
export function download(
  name: string,
  text: string,
  mime = "application/json",
) {
  const url = URL.createObjectURL(new Blob([text], { type: mime }));
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
