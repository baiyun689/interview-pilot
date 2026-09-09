// Local-only SMTP receiver for synthetic demo accounts. It never relays mail.
// Run from the repository root: node scripts/local-mail-sink.mjs
import net from 'node:net'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { randomUUID } from 'node:crypto'
const directory=resolve('.codex-local/mail')
mkdirSync(directory,{recursive:true})
net.createServer(socket=>{
  socket.setEncoding('utf8');socket.setTimeout(60000,()=>socket.destroy())
  let buffer='',data=false,lines=[],recipients=[]
  socket.write('220 localhost InterviewPilot local test SMTP\r\n')
  socket.on('data',chunk=>{
    buffer+=chunk
    if(buffer.length+lines.reduce((sum,line)=>sum+line.length,0)>262144){socket.end('552 Message too large\r\n');return}
    let end
    while((end=buffer.indexOf('\r\n'))>=0){
      const line=buffer.slice(0,end);buffer=buffer.slice(end+2)
      if(data){
        if(line==='.'){
          writeFileSync(resolve(directory,`${Date.now()}-${randomUUID()}.eml`),lines.join('\r\n'))
          console.log(`Captured local demo mail (${recipients.length} recipient)`)
          data=false;lines=[];recipients=[];socket.write('250 Accepted by local test receiver\r\n')
        }else lines.push(line.startsWith('..')?line.slice(1):line)
      }else if(/^(EHLO|HELO) /i.test(line))socket.write('250 localhost\r\n')
      else if(/^MAIL FROM:/i.test(line)){recipients=[];socket.write('250 OK\r\n')}
      else if(/^RCPT TO:/i.test(line)){
        if(/<[^<>\s]+@[^<>\s]+\.test>/i.test(line)){recipients.push(line);socket.write('250 OK\r\n')}
        else socket.write('550 Only synthetic .test recipients are accepted\r\n')
      }else if(/^DATA$/i.test(line)){
        if(recipients.length){data=true;socket.write('354 End with a single dot\r\n')}else socket.write('554 No test recipients\r\n')
      }else if(/^QUIT$/i.test(line)){socket.end('221 Bye\r\n')}
      else if(/^RSET$/i.test(line)){data=false;lines=[];recipients=[];socket.write('250 Reset\r\n')}
      else socket.write('250 OK\r\n')
    }
  })
  socket.on('error',()=>{})
}).listen(1025,'127.0.0.1',()=>console.log(`Local test SMTP listening on 127.0.0.1:1025; captures: ${directory}`))
