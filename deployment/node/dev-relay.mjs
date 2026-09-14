// Development only: REST relay/file copy, never a DTN/HDTN implementation.
import {createServer} from 'node:http';
import {createHash, timingSafeEqual} from 'node:crypto';
import {copyFile, rename, stat, realpath} from 'node:fs/promises';
import {constants} from 'node:fs';
import path from 'node:path';

const callback = process.env.RECEIVER_URL;
const token = process.env.RECEIVE_TOKEN;
const sendToken = process.env.SEND_TOKEN;
if (!callback || !token || !sendToken) throw new Error('Configure receiver URL and development tokens');
const jobs = new Map();
let busy = false;
const reply = (res, status, body) => { res.writeHead(status, {'Content-Type':'application/json'}); res.end(JSON.stringify(body)); };
const authorized = req => {
  const a = Buffer.from(req.headers.authorization || ''), b = Buffer.from('Bearer ' + sendToken);
  return a.length === b.length && timingSafeEqual(a, b);
};
async function relay(body, value) {
  try {
    if (value.testType === 'IQ_SAMPLE') {
      const name = path.basename(value.file.filePath);
      const source = path.join('/sender-files', name), destination = path.join('/receiver-files', name);
      const base = await realpath('/sender-files');
      if (path.dirname(await realpath(source)) !== base || !(await stat(source)).isFile()) throw new Error('Invalid source file');
      const metadata = await stat(source);
      if (metadata.size !== value.file.sizeBytes) throw new Error('Source size mismatch');
      await copyFile(source, destination + '.part', constants.COPYFILE_EXCL);
      await rename(destination + '.part', destination);
    }
    const response = await fetch(callback, {method:'POST', signal:AbortSignal.timeout(30000),
      headers:{'Content-Type':'application/json', Authorization:'Bearer ' + token}, body});
    if (!response.ok) throw new Error('Receiver HTTP ' + response.status);
    console.log(JSON.stringify({testId:value.testId,testType:value.testType,senderMode:value.senderMode,receiverMode:value.receiverMode,state:'DELIVERED',development:true}));
  } catch (error) {
    console.error(JSON.stringify({testId:value.testId,state:'FAILED',message:error.message,development:true}));
  } finally { busy = false; }
}
createServer(async (req,res) => {
  if (req.method === 'GET' && ['/sender/health','/receiver/health'].includes(req.url))
    return reply(res,200,{status:busy?'busy':'ready',development:true,message:'REST/file relay only; not DTN/HDTN'});
  if (req.method !== 'POST' || req.url !== '/transfers') return reply(res,404,{message:'Not found'});
  if (!authorized(req)) return reply(res,401,{message:'Unauthorized'});
  try {
    const chunks=[]; let size=0;
    for await (const chunk of req) { size+=chunk.length; if(size>16777216) return reply(res,413,{message:'JSON too large'}); chunks.push(chunk); }
    const body=Buffer.concat(chunks), value=JSON.parse(body.toString('utf8'));
    if (!['GNSS_RAW','AFS_METADATA','IQ_SAMPLE'].includes(value.testType)
        || !['DTN','HDTN'].includes(value.senderMode) || !['DTN','HDTN'].includes(value.receiverMode)
        || !/^[0-9a-f-]{36}$/i.test(value.testId)) return reply(res,400,{message:'Invalid dispatch fields'});
    if (value.testType === 'IQ_SAMPLE' && !/^\/exchange\/[0-9a-f-]{36}\.bin$/i.test(value.file?.filePath || ''))
      return reply(res,400,{message:'Invalid shared file path'});
    const hash=createHash('sha256').update(body).digest('hex');
    if (jobs.has(value.testId)) return reply(res,jobs.get(value.testId)===hash?202:409,{testId:value.testId,accepted:jobs.get(value.testId)===hash,development:true});
    if (busy) return reply(res,409,{message:'Development relay busy'});
    jobs.set(value.testId,hash); if(jobs.size>100) jobs.delete(jobs.keys().next().value);
    busy=true;
    reply(res,202,{testId:value.testId,accepted:true,development:true});
    void relay(body,value);
  } catch { reply(res,400,{message:'Invalid request'}); }
}).listen(8080,'0.0.0.0');
