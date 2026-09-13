// Temporary development helper. No example-specific behavior in the transfer pipeline.
export async function exampleFile() {
  const response = await fetch('/lnis/api/v1/dtn/example/file', {cache: 'no-store'});
  if (!response.ok) throw new Error('F9T 예제가 준비되지 않았습니다.');
  return new File([await response.blob()], 'f9t-example.graw', {type: 'application/octet-stream'});
}
