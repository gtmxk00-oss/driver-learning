const {createServer}=require('./server/http-server.cjs');
if(require.main===module){
  const server=createServer();
  server.listen(4173,'127.0.0.1',()=>console.log('Local: http://127.0.0.1:4173'));
  process.on('SIGINT',()=>server.close(()=>process.exit(0)));
}
module.exports={createServer};
