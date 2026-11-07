// Fixed base64 fixtures independently decoded by the server tests too. Author: Peppone Choi.
#pragma once
static bool countryImageDataTest() {
    BYTE record[24+10*36]={};
    auto word=[&](unsigned offset,unsigned value){memcpy(record+offset,&value,4);};
    auto start=[&](unsigned kind,unsigned number,unsigned count) {
        memset(record,0,sizeof(record));word(0,42);word(4,99);record[8]=17;record[9]=2;word(12,kind);word(16,number);record[20]=static_cast<BYTE>(count);
    };
    auto member=[&](unsigned slot,unsigned id,unsigned kind,const char* text) {
        auto p=record+24+slot*36;memcpy(p,&id,4);p[4]=static_cast<BYTE>(kind);
        const unsigned n=static_cast<unsigned>(strlen(text));p[6]=static_cast<BYTE>(n ? n+1 : 0);
        for(unsigned j=0;j<n;++j)p[8+j*2]=static_cast<BYTE>(text[j]);
    };
    auto header=[&](bool clear=false) {
        start(0,0,10);
        const char* fields[]={"CI1","19","",clear ? "2" : "4098",clear ? "3950255460" : "1164435191",clear ? "4" : "5464",clear ? "1" : "456","32","",""};
        for(unsigned j=0;j<10;++j)member(j,j+1,j,fields[j]);
    };
    logh::CountryImageDraft draft;draft.begin(42,98,19);header();
    if(draft.accept(record,sizeof(record)))return false;
    draft.begin(42,99,20);if(draft.accept(record,sizeof(record)))return false;
    draft.begin(42,99,19);if(draft.accept(record,sizeof(record))!=1)return false;
    start(1,1,10);for(unsigned j=0;j<10;++j)member(j,11+j,0,"AAAAAAAAAAAA");
    if(draft.accept(record,sizeof(record)))return false;
    for(unsigned page=0;page<46;++page) {
        const unsigned count=page==45 ? 6 : 10;start(1,page,count);
        for(unsigned j=0;j<count;++j) {
            const unsigned index=page*10+j;
            member(j,index+1,0,index==0 ? "AQEAAAAAAAAA" : index==455 ? "AAAA" : "AAAAAAAAAAAA");
        }
        if(draft.accept(record,sizeof(record))!=(page==45 ? 2 : 1))return false;
    }
    if(draft.value.flags!=1 || !draft.value.pixels(false) || draft.value.pixels(true)!=draft.value.pixels(false) || draft.accept(record,sizeof(record)))return false;
    for(auto b:draft.value.rgba)if(b)return false;
    resetCountryImages();publishCountryImages(draft.value);
    if(countryImagesNeeded(42,19) || !countryImagesNeeded(42,20))return false;
    auto stale=draft.value;stale.revision=18;publishCountryImages(stale);
    if(countryImagesNeeded(42,19))return false;
    draft.begin(42,99,19);header(true);if(draft.accept(record,sizeof(record))!=1)return false;
    start(1,0,1);member(0,1,0,"AQB=");if(draft.accept(record,sizeof(record)))return false; // noncanonical padding
    draft.begin(42,99,19);header(true);if(draft.accept(record,sizeof(record))!=1)return false;
    start(1,0,1);member(0,1,0,"AQA=");if(draft.accept(record,sizeof(record))!=2 || draft.value.flags || draft.value.pixels(true))return false;
    publishCountryImages(draft.value);resetCountryImages();return countryImagesNeeded(42,19);
}
