/* Exercises the real tuner code with a deterministic I2C bus. No RF hardware needed. */
#include <assert.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#ifndef TUNER_SOURCE
#define TUNER_SOURCE "../../main/cpp/third_party/rtl_sdr_blog/src/tuner_r82xx.c"
#endif
#include TUNER_SOURCE

struct bus {
    uint8_t regs[64];
    int writes, reads, gpio, hf_transients, monitor_hf, fail_next;
    const char *model;
};
int rtlsdr_check_dongle_model(void *dev, char *manufacturer, char *product) {
    (void)manufacturer;
    return !strcmp(((struct bus*)dev)->model, product);
}
int rtlsdr_set_bias_tee_gpio(void *dev, int gpio, int on) {
    (void)gpio; (void)on; ((struct bus*)dev)->gpio++; return 0;
}
uint32_t rtlsdr_get_tuner_clock(void *dev) { (void)dev; return 28800000; }
int rtlsdr_i2c_write_fn(void *dev, uint8_t addr, uint8_t *buf, int len) {
    (void)addr;
    struct bus *b = dev;
    b->writes++;
    if (b->fail_next) { b->fail_next = 0; return -5; }
    for (int i=1; i<len; i++) {
        unsigned reg=buf[0]+i-1;
        b->regs[reg]=buf[i];
        if (b->monitor_hf && ((reg==0x1a && (buf[i]&0xc3)!=0x40) || (reg==0x1b && buf[i]!=0))) b->hf_transients++;
    }
    return len;
}
int rtlsdr_i2c_read_fn(void *dev, uint8_t addr, uint8_t *buf, int len) {
    (void)addr;
    ((struct bus*)dev)->reads++;
    memset(buf,0,len);
    if (len>2) buf[2]=r82xx_bitrev(0x40); // PLL locked.
    if (len>4) buf[4]=r82xx_bitrev(0x10); // VCO fine tune/reference.
    return len;
}
int main(void) {
    const char *models[]={"Blog V4","Blog V4L","Generic"};
    const uint32_t frequencies[]={7000000,7001000,7002000,7003000,2199000,2201000,28800000,28801000,
        84999000,85001000,111999000,112001000,171999000,172001000,241999000,242001000,
        249999000,250001000,433000000,1766000000,7100000,7101000};
    for (unsigned model=0; model<3; model++) {
        struct bus b={0}; b.model=models[model];
        struct r82xx_config config={0}; config.i2c_addr=0x74; config.xtal=28800000;
        config.rafael_chip=model==1?CHIP_R820T:CHIP_R828D; config.max_i2c_msg_len=8;
        struct r82xx_priv priv={0}; priv.cfg=&config; priv.rtl_dev=&b; priv.int_freq=3570000;
        priv.xtal_cap_sel=XTAL_LOW_CAP_0P;
        memcpy(priv.regs,r82xx_init_array,sizeof(priv.regs));
        memcpy(b.regs+REG_SHADOW_START,r82xx_init_array,sizeof(priv.regs));
        for (unsigned f=0; f<sizeof(frequencies)/sizeof(frequencies[0]); f++) {
            b.writes=b.reads=b.gpio=b.hf_transients=0;
            b.monitor_hf=(model<2 && f>0 && frequencies[f]<28800000 && frequencies[f-1]<28800000);
            assert(r82xx_set_freq(&priv,frequencies[f])==0);
            assert(priv.has_lock);
            if (model<2 && frequencies[f]<=28800000) {
                assert((r82xx_read_cache_reg(&priv,0x1a)&0xc3)==0x40);
                assert(r82xx_read_cache_reg(&priv,0x1b)==0);
            }
            if (model==0) {
                int expected_notch = (frequencies[f]<=2200000 || (frequencies[f]>=85000000 && frequencies[f]<=112000000)
                    || (frequencies[f]>=172000000 && frequencies[f]<=242000000)) ? 0 : 8;
                assert((r82xx_read_cache_reg(&priv,0x17)&8)==expected_notch);
            }
#ifndef BASELINE
            assert(b.hf_transients==0);
#endif
            printf("%s %u IO=%d HF_transients=%d registers=",b.model,frequencies[f],b.writes+b.reads,b.hf_transients);
            for (int i=0;i<NUM_REGS;i++) printf("%02x",priv.regs[i]);
            puts("");
        }
#ifndef BASELINE
        b.monitor_hf=0;
        int cached=r82xx_read_cache_reg(&priv,0x0c);
        b.fail_next=1;
        assert(r82xx_write_stable_mask(&priv,0x0c,cached^1,0xff)==-5);
        assert(r82xx_read_cache_reg(&priv,0x0c)==cached);
        assert(r82xx_write_stable_mask(&priv,0x0c,cached^1,0xff)==0);
        assert(r82xx_read_cache_reg(&priv,0x0c)==(cached^1));
#endif
    }
    puts("PASS: frequency/band transitions, PLL checks, HF path stability and failed-write recovery");
}
