package kr.routememory.shared;

import java.io.IOException;

/** One shared start-rate gate for explicit text and nearby requests to the public Photon service. */
final class PhotonRequestGate {
    private static long lastStart=System.nanoTime()-1000000000L;
    private PhotonRequestGate(){}
    static synchronized void await()throws IOException{
        long wait=1000000000L-(System.nanoTime()-lastStart);
        if(wait>0)try{Thread.sleep(wait/1000000L,(int)(wait%1000000L));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("검색을 취소했습니다");}
        if(Thread.currentThread().isInterrupted())throw new IOException("검색을 취소했습니다");
        lastStart=System.nanoTime();
    }
}
