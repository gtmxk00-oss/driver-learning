'use strict';
const http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const {createTrafficProvider,TrafficError}=require('./traffic-provider.cjs');
const {createRoadProvider,RoadError}=require('./road-provider.cjs');
const {createPlaceProvider,PlaceError}=require('./place-provider.cjs');
const {createNearbyProvider,NearbyError}=require('./nearby-provider.cjs');
const {createVworldProvider,MapError}=require('./vworld-provider.cjs');

function createHandler({provider=createTrafficProvider(),roadProvider=createRoadProvider(),placeProvider=createPlaceProvider(),nearbyProvider=createNearbyProvider(),mapProvider=createVworldProvider(),file=path.join(__dirname,'../dist/index.html')}={}){
  function json(res,status,value){res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});res.end(JSON.stringify(value));}
  function localRequest(req){
    const host=req.headers.host,port=req.socket.localPort||4173;
    if(!['127.0.0.1:'+port,'localhost:'+port,'[::1]:'+port].includes(host))return false;
    if(req.headers.origin&&req.headers.origin!=='http://'+host)return false;
    if(req.headers['sec-fetch-site']==='cross-site')return false;
    return true;
  }
  async function readJSON(req){
    if(!/^application\/json(?:\s*;|$)/i.test(req.headers['content-type']||''))throw new TrafficError(415,'JSON_REQUIRED','JSON 형식으로 요청해주세요.');
    const expected=Number(req.headers['content-length']);
    if(Number.isFinite(expected)&&expected>8192)throw new TrafficError(413,'TOO_LARGE','요청 크기가 너무 큽니다.');
    const chunks=[];let size=0;
    for await(const chunk of req){size+=Buffer.byteLength(chunk);if(size>8192)throw new TrafficError(413,'TOO_LARGE','요청 크기가 너무 큽니다.');chunks.push(Buffer.from(chunk));}
    try{return JSON.parse(Buffer.concat(chunks).toString('utf8'));}catch{throw new TrafficError(400,'INVALID_JSON','출발지와 목적지 요청을 확인해주세요.');}
  }
  return async function handler(req,res){
    if(req.url.startsWith('/api/')){
      if(!localRequest(req)){json(res,403,{error:{code:'LOCAL_ONLY',message:'이 API는 같은 컴퓨터의 시연 화면에서만 사용할 수 있습니다.'}});return;}
      try{
        if(req.method==='GET'&&req.url==='/api/traffic/status'){json(res,200,{configured:provider.configured,provider:'kakao'});return;}
        if(req.method==='POST'&&req.url==='/api/traffic/routes'){json(res,200,await provider.routes(await readJSON(req)));return;}
        if(req.method==='GET'&&req.url==='/api/navigation/status'){json(res,200,{configured:roadProvider.configured,provider:'osrm',traffic:'unavailable'});return;}
        if(req.method==='POST'&&req.url==='/api/navigation/route'){json(res,200,await roadProvider.route(await readJSON(req)));return;}
        if(req.method==='GET'&&req.url==='/api/places/status'){json(res,200,{configured:placeProvider.configured,provider:'photon'});return;}
        if(req.method==='POST'&&req.url==='/api/places/search'){json(res,200,await placeProvider.search(await readJSON(req)));return;}
        if(req.method==='GET'&&req.url==='/api/nearby/status'){json(res,200,nearbyProvider.status());return;}
        if(req.method==='POST'&&req.url==='/api/nearby/search'){json(res,200,await nearbyProvider.search(await readJSON(req)));return;}
        if(req.method==='GET'&&req.url==='/api/maps/status'){json(res,200,mapProvider.status());return;}
        if(req.method==='GET'&&req.url.startsWith('/api/maps/vworld/')){
          const parts=req.url.slice('/api/maps/vworld/'.length).split('/');
          if(parts.length!==3)throw new MapError(400,'INVALID_TILE','올바른 지도 타일 좌표가 필요합니다.');
          const tile=await mapProvider.tile(...parts);
          res.writeHead(200,{'Content-Type':'image/jpeg','Content-Length':tile.body.length,'Cache-Control':tile.cacheControl,'X-Content-Type-Options':'nosniff'});res.end(tile.body);return;
        }
        json(res,404,{error:{code:'NOT_FOUND',message:'지원하지 않는 요청입니다.'}});
      }catch(e){
        if(e instanceof TrafficError||e instanceof RoadError||e instanceof PlaceError||e instanceof NearbyError||e instanceof MapError)json(res,e.status,{error:{code:e.code,message:e.message}});
        else json(res,500,{error:{code:'SERVER_ERROR',message:'요청을 처리하지 못했습니다. 잠시 후 다시 시도해주세요.'}});
      }
      return;
    }
    if(req.url==='/favicon.ico'){res.writeHead(204);res.end();return;}
    if(req.method!=='GET'||!['/','/index.html'].includes(req.url)){res.writeHead(404);res.end('Not found');return;}
    try{res.writeHead(200,{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});res.end(fs.readFileSync(file));}
    catch{res.writeHead(503);res.end('Build the PC demo first: npm run build');}
  };
}
function createServer(options){return http.createServer(createHandler(options));}
module.exports={createHandler,createServer};
