/* 자동시험용 합성 GPS 관측값이다. 실제 EVK-F9T/우주환경 검증을 대체하지 않는다. */
#include "lnis_pvt.c"
#define assert(c) do { if (!(c)) { fprintf(stderr,"check failed: %s line %d\n",#c,__LINE__); return 1; } } while(0)

int main(void) {
    lnis_pvt_context *a = lnis_pvt_create(), *b = lnis_pvt_create();
    double obs[32*4], result_a[9], result_b[9], pos[3]={37.5*D2R,127.0*D2R,100}, rr[3];
    char message[256];
    int prn, sub, word, j, n=0, week=2400;
    gtime_t time=gpst2time(week,100000);
    assert(a && b);
    pos2ecef(pos,rr);
    FILE *fixture = fopen("dtn-synthetic-gps.txt","w");
    assert(fixture);
    fprintf(fixture,"R %.15g %.15g %.15g\n",rr[0],rr[1],rr[2]);
    for(prn=1;prn<=32;prn++) {
        uint8_t frame[150]={0};
        double phase=-PI+(prn-1)*2*PI/32;
        for(sub=0;sub<3;sub++) {
            setbitu(frame,sub*240,8,0x8b);
            setbitu(frame,sub*240+24,17,16667+sub);
            setbitu(frame,sub*240+43,3,sub+1);
        }
        setbitu(frame,48,10,week%1024);
        setbitu(frame,58,2,1);
        setbitu(frame,168,8,1);
        setbitu(frame,176,16,99984/16);
        setbitu(frame,288,8,1);
        setbits(frame,328,32,(int32_t)(phase/(P2_31*SC2RAD)));
        setbitu(frame,376,32,(uint32_t)(0.01/P2_33));
        setbitu(frame,424,32,(uint32_t)(sqrt(26560000.0)/P2_19));
        setbitu(frame,456,16,99984/16);
        setbits(frame,544,32,(int32_t)((-PI+((prn-1)%6)*PI/3)/(P2_31*SC2RAD)));
        setbits(frame,592,32,(int32_t)((55*D2R)/(P2_31*SC2RAD)));
        setbitu(frame,696,8,1);
        for(sub=0;sub<3;sub++) {
            uint32_t words[10];
            for(word=0;word<10;word++) words[word]=getbitu(frame,sub*240+word*24,24);
            fprintf(fixture,"N %d",prn);
            for(word=0;word<10;word++) fprintf(fixture," %u",words[word]<<6);
            fprintf(fixture,"\n");
            int status=lnis_pvt_gps_navigation(a,prn,week,words,10);
            assert(status>=0);
            assert(lnis_pvt_gps_navigation(b,prn,week,words,10)==status);
            if(sub==2) assert(status==1);
        }
        double rs[3],dts,var,e[3],azel[2],range=23000000;
        for(j=0;j<5;j++) {
            eph2pos(timeadd(time,-range/CLIGHT),a->nav.eph+prn-1,rs,&dts,&var);
            range=geodist(rs,rr,e);
        }
        satazel(pos,e,azel);
        if(azel[1]<20*D2R) continue;
        obs[n*4]=prn;
        obs[n*4+1]=range-CLIGHT*dts+ionmodel(time,a->nav.ion_gps,pos,azel)+tropmodel(time,pos,azel,0.7);
        obs[n*4+2]=-1000.0+prn*30;
        obs[n*4+3]=45;
        fprintf(fixture,"O %d %.15g %.15g 45\n",prn,obs[n*4+1],obs[n*4+2]);
        n++;
    }
    assert(n>=4);
    fclose(fixture);
    int status=lnis_pvt_gps_solve(a,week,100000,obs,n,result_a,message,sizeof(message));
    printf("PVT status=%d visible=%d message=%s\n",status,n,message);
    assert(status==1);
    assert(lnis_pvt_gps_solve(b,week,100000,obs,n,result_b,message,sizeof(message))==1);
    double error=0;
    for(j=0;j<3;j++) error+=pow(result_a[j]-rr[j],2);
    printf("Position error=%g m velocity_valid=%g\n",sqrt(error),result_a[8]);
    assert(sqrt(error)<100);
    assert(result_a[8]==1);
    for(j=0;j<9;j++) assert(result_a[j]==result_b[j]);

    /* 범용 lnis_pvt_solve 5-tuple 검증 */
    double obs5[32*5], result_multi[9];
    for(j=0;j<n;j++) {
        obs5[j*5+0] = LNIS_SYS_GPS;
        obs5[j*5+1] = obs[j*4+0];
        obs5[j*5+2] = obs[j*4+1];
        obs5[j*5+3] = obs[j*4+2];
        obs5[j*5+4] = obs[j*4+3];
    }
    status = lnis_pvt_solve(a, LNIS_SYS_GPS, week, 100000, obs5, n, result_multi, message, sizeof(message));
    assert(status == 1);
    for(j=0;j<9;j++) assert(result_a[j] == result_multi[j]);

    lnis_pvt_destroy(a); lnis_pvt_destroy(b);
    puts("Native PVT synthetic test passed.");
    return 0;
}
