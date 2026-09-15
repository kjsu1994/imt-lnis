/* LNIS 소유 연결부: 달 샘플 대신 GNSS 지구 PVT와 LNAV를 원본 AFS 변조기에 공급한다.
 * 원본 복사본이 아닌 서비스 구현이며 공유 DLL ABI/원본 변조 알고리즘은 변경하지 않는다. */
#include "lnis_pvt.c"
#include <omp.h>
static lnis_pvt_context *earth;
static double origin[3], motion[3], start_tow, bias;
static int start_week, selected[32];

int iq_earth_load(const char *path, int *week, double *tow, double *xyz, double *llh) {
    /* Upstream modulation uses shared ph and RNG state. Preserve its algorithms
       while avoiding OpenMP data races; do not rewrite the waveform generator. */
    omp_set_num_threads(1);
    FILE *f = fopen(path, "r"); char magic[32], kind; int prn, count=0;
    if (!f) return -1;
    if (fscanf(f,"%31s %d %lf %lf %lf %lf %lf %lf %lf %lf",magic,&start_week,&start_tow,
        &origin[0],&origin[1],&origin[2],&motion[0],&motion[1],&motion[2],&bias)!=10 ||
        strcmp(magic,"LNIS-IQ-EARTH-1") || start_week<0 || start_week>8191 ||
        !isfinite(start_tow) || start_tow<0 || start_tow>=604800) { fclose(f); return -1; }
    for(int i=0;i<3;i++) if(!isfinite(origin[i]) || !isfinite(motion[i])) { fclose(f); return -1; }
    if(!isfinite(bias) || norm(origin,3)<6e6 || norm(origin,3)>7e6) { fclose(f); return -1; }
    earth=lnis_pvt_create(); if(!earth) { fclose(f); return -1; }
    while(fscanf(f," %c %d",&kind,&prn)==2) {
        if(prn<1 || prn>32) { fclose(f); return -1; }
        if(kind=='P') selected[prn-1]=1;
        else if(kind=='N') {
            uint32_t words[10];
            for(int j=0;j<10;j++) if(fscanf(f,"%u",&words[j])!=1) { fclose(f); return -1; }
            if(lnis_pvt_gps_navigation(earth,prn,start_week,words,10)<0) { fclose(f); return -1; }
        } else { fclose(f); return -1; }
    }
    fclose(f);
    *week=start_week; *tow=start_tow; memcpy(xyz,origin,sizeof(origin)); ecef2pos(xyz,llh);
    for(int i=0;i<32;i++) {
        eph_t *e=&earth->nav.eph[satno(SYS_GPS,i+1)-1];
        if(selected[i] && (e->A<=0 || e->svh || fabs(timediff(gpst2time(start_week,start_tow),e->toe))>7200)) return -1;
        if(selected[i]) count++;
    }
    return count>=4 ? count : -1;
}

int iq_earth_fields(int prn,double *v) {
    if(!earth || prn<1 || prn>32 || !selected[prn-1]) return 0;
    eph_t *e=&earth->nav.eph[satno(SYS_GPS,prn)-1];
    v[0]=time2gpst(e->toe,NULL); v[1]=time2gpst(e->toc,NULL);
    v[2]=e->e; v[3]=sqrt(e->A); v[4]=e->M0; v[5]=e->OMG0; v[6]=e->i0;
    v[7]=e->omg; v[8]=e->f0; v[9]=e->f1;
    return 1;
}

void iq_earth_receiver(int week,double tow,double *xyz) {
    double dt=(week-start_week)*604800.0+tow-start_tow;
    for(int i=0;i<3;i++) xyz[i]=origin[i]+motion[i]*dt;
}

void iq_earth_sat(int prn,int week,double tow,double *pos,double *vel,double *clk) {
    eph_t *e=&earth->nav.eph[satno(SYS_GPS,prn)-1];
    double next[3], c1, variance; gtime_t t=gpst2time(week,tow);
    eph2pos(t,e,pos,&clk[0],&variance);
    eph2pos(timeadd(t,0.001),e,next,&c1,&variance);
    for(int i=0;i<3;i++) vel[i]=(next[i]-pos[i])/0.001;
    clk[1]=(c1-clk[0])/0.001;
}

double iq_earth_range(int prn,int week,double tow) {
    double xyz[3], pos[3], vel[3], clk[2], los[3], tau=0.075, range=0;
    iq_earth_receiver(week,tow,xyz);
    for(int j=0;j<3;j++) {
        iq_earth_sat(prn,week,tow-tau,pos,vel,clk);
        range=geodist(pos,xyz,los); tau=range/CLIGHT;
    }
    eph_t *e=&earth->nav.eph[satno(SYS_GPS,prn)-1];
    /* Geometric/Sagnac + satellite clock/TGD + constant receiver clock bias.
       Atmospheric effects and RF receiver noise are not reconstructed from RAWX. */
    return range+CLIGHT*(bias-clk[0]+e->tgd[0]);
}
