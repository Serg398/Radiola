#include "rf_gain_controller.h"
#include <cassert>
#include <cstdio>
#include <vector>
#include <utility>

int main() {
    RfGainController controller;
    std::vector<std::pair<char,int>> calls;
    int error=0;
    auto mode=[&](int value) { calls.emplace_back('m',value); return error; };
    auto gain=[&](int value) { calls.emplace_back('g',value); return error; };
    assert(controller.apply(false,229,true,mode,gain)==0);
    assert(calls.size()==1 && calls[0]==std::make_pair('g',229));
    calls.clear();
    // Audio AGC and bias-T toggles keep exactly the same RF request.
    for (int i=0;i<20;i++) assert(controller.apply(false,229,true,mode,gain)==0);
    assert(calls.empty());
    assert(controller.apply(false,297,true,mode,gain)==0);
    assert(calls.size()==1 && calls[0]==std::make_pair('g',297));
    calls.clear();
    assert(controller.apply(true,297,true,mode,gain)==0);
    assert(calls.size()==1 && calls[0]==std::make_pair('m',0));
    calls.clear();
    assert(controller.apply(true,229,true,mode,gain)==0);
    assert(calls.empty());
    assert(controller.apply(false,229,true,mode,gain)==0);
    assert(calls.size()==1 && calls[0]==std::make_pair('g',229));
    calls.clear(); error=-5;
    assert(controller.apply(false,297,true,mode,gain)==-5);
    error=0;
    assert(controller.apply(false,297,true,mode,gain)==0);
    assert(calls.size()==2);
    controller.reset(); calls.clear();
    assert(controller.apply(false,197,false,mode,gain)==0);
    assert(calls.size()==2 && calls[0]==std::make_pair('m',1) && calls[1]==std::make_pair('g',197));
    puts("PASS: independent audio controls, RF deduplication, direct manual transition, reset and retry");
}
