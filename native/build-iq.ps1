param([Parameter(Mandatory=$true)][string]$OpenSourceDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$sourceRoot = Join-Path $OpenSourceDirectory 'LANS-AFS-SIM-main'
$stage = Join-Path $projectRoot ('build/iq-source-' + [Guid]::NewGuid().ToString('N'))
$output = Join-Path $projectRoot 'build/iq'
New-Item -ItemType Directory -Path $stage,$output -Force | Out-Null
foreach ($name in @('afs_sim.c','afs_nav.c','afs_nav.h','afs_rand.c','afs_rand.h','makefile','LICENSE.txt','default_almanac.txt','008_Weil1500hex210prns.txt','ldpc','rtklib','pocketsdr')) {
    Copy-Item -LiteralPath (Join-Path $sourceRoot $name) -Destination $stage -Recurse
}
Copy-Item -LiteralPath (Join-Path $OpenSourceDirectory 'PocketSDR-AFS-main/lib/RTKLIB/src') -Destination (Join-Path $stage 'rtk') -Recurse
foreach ($name in @('iq_earth.c','lnis_pvt.c','lnis_pvt.h','lnis_afs_codec.h')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $stage }
# The supplied fork has broken logging literals; remove only these logging bodies in the build copy.
$source = Join-Path $stage 'afs_nav.c'
$contents = [IO.File]::ReadAllText($source)
$contents = [regex]::Replace($contents, 'static void bits_to_hex\(.*?\r?\n\}', 'static void bits_to_hex(const uint8_t* bits, int len, char* hex) { (void)bits; (void)len; if (hex) hex[0] = 0; }', [Text.RegularExpressions.RegexOptions]::Singleline)
$contents = [regex]::Replace($contents, 'void log_AFS_bits\(.*?\r?\n\}', 'void log_AFS_bits(FILE* fp, const char* id, int prn, int toi, int sb, const char* stage, const uint8_t* bits, int len) { (void)fp; (void)id; (void)prn; (void)toi; (void)sb; (void)stage; (void)bits; (void)len; }', [Text.RegularExpressions.RegexOptions]::Singleline)
[IO.File]::WriteAllText($source, $contents, [Text.UTF8Encoding]::new($false))
# Each loop writes 0.1 seconds. The upstream subtraction produces 89.9 seconds for -t 90.
$simSource = Join-Path $stage 'afs_sim.c'
$simContents = [IO.File]::ReadAllText($simSource)
$oldCount = 'nsim = (int)((tsec - 0.1) * 10.0);'
if (!$simContents.Contains($oldCount)) { throw 'Review the upstream simulator duration loop before building.' }
$simContents = $simContents.Replace($oldCount, 'nsim = (int)llround(tsec * 10.0);')
$simContents = $simContents.Replace('#define MAX_SAT (12)', '#define MAX_SAT (32)')
$simContents = $simContents.Replace('int vflg;', 'int vflg; int earth_prn;')
$prototypes = @'
int iq_earth_load(const char*,int*,double*,double*,double*);
int iq_earth_fields(int,double*);
void iq_earth_receiver(int,double,double*);
void iq_earth_sat(int,int,double,double*,double*,double*);
double iq_earth_range(int,int,double);
'@
$simContents = $prototypes + "`n" + $simContents
# Keep upstream channel modulation, FEC, interleaving, repeated data and TOI update.
# Replace only the Earth input/orbit/range boundary in a disposable build copy.
$simContents = [regex]::Replace($simContents, '(void satpos\([^\{]+\{)', '$1' + "`n    iq_earth_sat(eph.earth_prn, g.week, g.sec, pos, vel, clk); return;", 1)
$simContents = [regex]::Replace($simContents, '(void computeRange\([^\{]+\{)', '$1' + "`n    rho->range=iq_earth_range(eph.earth_prn,g.week,g.sec); rho->rate=(iq_earth_range(eph.earth_prn,g.week,g.sec+0.001)-rho->range)/0.001; rho->g=g; return;", 1)
$initialization = @'
    // Required GNSS-derived input; never silently fall back to lunar sample data.
    neph=iq_earth_load(feph,&g0.week,&g0.sec,xyz,llh);
    if(neph<4) { fprintf(stderr,"ERROR: Earth input requires valid PVT and GPS LNAV for at least four observed PRNs.\n"); return 2; }
    memset(eph,0,sizeof(eph));
    for(sv=0;sv<MAX_SAT;sv++) {
        double v[10]; if(!iq_earth_fields(sv+1,v)) continue;
        eph[sv].vflg=1; eph[sv].earth_prn=sv+1;
        eph[sv].toe.week=eph[sv].toc.week=g0.week;
        eph[sv].toe.sec=v[0]; eph[sv].toc.sec=v[1];
        eph[sv].ecc=v[2]; eph[sv].sqrta=v[3]; eph[sv].m0=v[4]; eph[sv].omg0=v[5];
        eph[sv].inc0=v[6]; eph[sv].aop=v[7]; eph[sv].af0=v[8]; eph[sv].af1=v[9];
    }
    afstime_t afst; gpst2afst(&g0,&afst);
    fprintf(stderr,"EARTH GNSS input: week=%d tow=%.9f xyz=%.3f %.3f %.3f\n",g0.week,g0.sec,xyz[0],xyz[1],xyz[2]);
    // Check visible satellites
'@
$pattern='(?s)    // Set user location.*?    // Check visible satellites'
if ([regex]::Matches($simContents,$pattern).Count -ne 1) { throw 'Review upstream initialization' }
$simContents=[regex]::Replace($simContents,$pattern,[System.Text.RegularExpressions.MatchEvaluator]{param($m) $initialization})
# Avoid collisions with the full RTKLIB symbols; only rename simulator-local calls.
$simContents=[regex]::Replace($simContents,'\btimeadd\b','sim_timeadd')
$simContents=[regex]::Replace($simContents,'\bsatpos\b','sim_satpos')
$frameLine='bitncpy(chan[i].I.data[0] + 120, syms, 5880);'
$simContents=$simContents.Replace($frameLine,$frameLine + @'

        char evidence_name[64]; snprintf(evidence_name,sizeof(evidence_name),"prn-%02d.afsbits",chan[i].prn);
        FILE *evidence=fopen(evidence_name,"wb");
        if(!evidence || fwrite(chan[i].I.data[0],1,6000,evidence)!=6000) { fprintf(stderr,"Frame evidence write failed\n"); return 2; }
        fclose(evidence);
'@)
[IO.File]::WriteAllText($simSource, $simContents, [Text.UTF8Encoding]::new($false))
docker run --rm --network none --mount "type=bind,source=$stage,target=/src" -w /src gcc:14 gcc -O2 -static -fopenmp -ffunction-sections -fdata-sections '-Wl,--gc-sections' -Ildpc -Irtk -Ipocketsdr afs_sim.c iq_earth.c afs_nav.c afs_rand.c ldpc/alloc.c ldpc/mod2sparse.c rtk/rtkcmn.c rtk/rcvraw.c rtk/ephemeris.c pocketsdr/pocketsdr.c -lm -o afs_sim
if ($LASTEXITCODE -ne 0) { throw "I/Q build failed: $stage" }
docker run --rm --network none --mount "type=bind,source=$stage,target=/src" -w /src gcc:14 ./afs_sim -t 0.1 -b 2 missing-input.bin
if ($LASTEXITCODE -eq 0) { throw 'Earth I/Q must reject missing GNSS input.' }
foreach ($name in @('afs_sim','default_almanac.txt','008_Weil1500hex210prns.txt','LICENSE.txt')) {
    Copy-Item -LiteralPath (Join-Path $stage $name) -Destination $output -Force
}
Compress-Archive -LiteralPath $stage -DestinationPath (Join-Path $output 'sources.zip') -Force
Write-Output "I/Q binary and source bundle: $output"
