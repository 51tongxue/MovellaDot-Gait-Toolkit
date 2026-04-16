#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
步态算法端到端深度测试工具

这不仅是一个画图工具，它现在 100% 模拟 Android App 的运行逻辑：
1. 直接调用 gait_analyzer.py 中的 process_gait_data 主流程。
2. 支持跳远单边截断、三级跳伴飞串扰清洗。
3. 打印出终端步幅、腾空时间等物理分析报告。
4. 绘制对齐后的事件图表供复查。
"""

import os
import sys
import subprocess
import tempfile
import argparse
import json
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gait_analyzer import process_gait_data

DEVICE_OFFLINE_DIR = "/storage/emulated/0/Documents/XsensData/offline_export"
DEVICE_ONLINE_DIR  = "/storage/emulated/0/Documents/XsensData/data_logging"
LOCAL_PULL_DIR     = os.path.join(tempfile.gettempdir(), "gait_debug")

def _adb_check():
    try:
        result = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=5)
        lines = [l.strip() for l in result.stdout.splitlines() if l.strip() and "List" not in l]
        if not lines:
            print("[adb] 未检测到已连接设备")
            return False
        return True
    except FileNotFoundError:
        return False

def _adb_ls(remote_dir):
    try:
        result = subprocess.run(["adb", "shell", f"ls {remote_dir}"], capture_output=True, text=True, timeout=10)
        if result.returncode != 0: return []
        return [f.strip() for f in result.stdout.splitlines() if f.strip().lower().endswith(".csv")]
    except: return []

def _adb_pull(remote_path, local_path):
    os.makedirs(os.path.dirname(local_path), exist_ok=True)
    result = subprocess.run(["adb", "pull", remote_path, local_path], capture_output=True, text=True, timeout=30)
    return result.returncode == 0

def pick_file_from_device():
    if not _adb_check(): return None
    all_files = []
    for label, remote_dir in [("离线", DEVICE_OFFLINE_DIR), ("在线", DEVICE_ONLINE_DIR)]:
        files = _adb_ls(remote_dir)
        for f in files: all_files.append((label, remote_dir, f))
    if not all_files:
        print("[adb] 设备上未找到 CSV 文件")
        return None
    print("\n设备上的 CSV 文件：")
    for i, (label, _, fname) in enumerate(all_files):
        print(f"  [{i}] {label}  {fname}")
    while True:
        choice = input("\n请输入编号选择主脚文件（如果你有多脚数据，只需选一侧，它会自动拉取另一侧）（回车退出）: ").strip()
        if not choice: return None
        try:
            idx = int(choice)
            if 0 <= idx < len(all_files): break
        except: pass
    
    label, remote_dir, fname = all_files[idx]
    
    parts = fname.replace('.csv', '').split('_')
    if len(parts) >= 3:
        mac = parts[0]
        date_str = parts[1]
        time_str = parts[2]
        opposite_mac = 'D422CD007E6E' if '937F' in mac else 'D422CD00937F'
        try:
            t1 = int(time_str[:2])*3600 + int(time_str[2:4])*60 + int(time_str[4:6])
            
            for _, _, other_f in all_files:
                if other_f.startswith(opposite_mac) and date_str in other_f:
                    o_parts = other_f.replace('.csv', '').split('_')
                    if len(o_parts) >= 3:
                        try:
                            t2 = int(o_parts[2][:2])*3600 + int(o_parts[2][2:4])*60 + int(o_parts[2][4:6])
                            if abs(t1 - t2) <= 10:
                                print(f"自动发现对侧数据，正在一并拉取: {other_f}")
                                _adb_pull(f"{remote_dir}/{other_f}", os.path.join(LOCAL_PULL_DIR, other_f))
                        except: pass
        except: pass
    
    remote_path = f"{remote_dir}/{fname}"
    local_path  = os.path.join(LOCAL_PULL_DIR, fname)
    if _adb_pull(remote_path, local_path):
        return local_path
    return None

def print_metrics(side, strides):
    print(f"\n====================== {side}脚物理步态报告 ======================")
    if not strides:
        print("无有效步态周期。")
        return
    
    headers = ["Stride", "Time(ms)", "Contact(ms)", "Swing(ms)", "Flight(ms)", "Len(m)", "Speed(m/s)"]
    print(f"{headers[0]:<8} | {headers[1]:<10} | {headers[2]:<12} | {headers[3]:<10} | {headers[4]:<11} | {headers[5]:<8} | {headers[6]:<10}")
    print("-" * 80)
    for i, s in enumerate(strides):
        st = f"{s.get('stride_time_s',0)*1000:.0f}"
        ct = f"{s.get('contact_time_s',0)*1000:.0f}"
        swt = f"{s.get('swing_time_s',0)*1000:.0f}"
        flt = f"{s.get('flight_time_s',0)*1000:.0f}"
        slen = f"{s.get('stride_length_m',0):.2f}"
        svel = f"{s.get('stride_velocity_mps',0):.2f}"
        print(f"#{i+1:<7} | {st:<10} | {ct:<12} | {swt:<10} | {flt:<11} | {slen:<8} | {svel:<10}")
    print("==================================================================\n")

def plot_single(ax, idata, events, title_text):
    t_sec = np.array(idata['timestamps']) / 1000.0
    gmax = np.array(idata['gyro_y'])
    acc_z = np.array(idata['acc_z'])
    
    ax.plot(t_sec, gmax, color='#c0baca', linewidth=2, label='Gmax (°/s)', alpha=0.9)
    
    ax_acc = ax.twinx()
    ax_acc.plot(t_sec, acc_z, color='#87CEFA', linewidth=1.5, label='ACC.Z (g)', alpha=0.6, zorder=1)
    ax_acc.set_ylabel('垂直加速度 (g)', fontsize=9, fontweight='bold', color='#87CEFA')
    ax_acc.spines['top'].set_visible(False)
    ax_acc.tick_params(axis='y', labelcolor='#87CEFA')
    ax_acc.legend(loc='upper left', frameon=False)

    s = 70
    def plot_events(event_list, marker, color, label):
        y_vals = []
        t_vals = []
        for t in event_list:
            idx = (np.abs(np.array(idata['timestamps']) - t)).argmin()
            y_vals.append(gmax[idx])
            t_vals.append(t/1000.0)
        ax.scatter(t_vals, y_vals, edgecolors=color, facecolors='none', marker=marker, s=s, zorder=5, label=label, linewidths=1.5)

    plot_events(events.get('msw', []), 's', 'black', 'MSW')
    plot_events(events.get('hs', []), 'o', 'red', 'IC(HS)')
    plot_events(events.get('to', []), '^', 'green', 'TC(TO)')
    plot_events(events.get('ms', []), 'v', '#FFA500', 'MS')

    ax.set_ylabel('角速度 Y (°/s)', fontsize=9, fontweight='bold')
    ax.legend(loc='upper right', frameon=False)
    ax.spines['top'].set_visible(False)
    ax.spines['right'].set_visible(False)
    
    n_hs = len(events.get('hs', []))
    n_to = len(events.get('to', []))
    n_ms = len(events.get('ms', []))
    ax.set_title(f"{title_text}  [IC={n_hs} TC={n_to} MS={n_ms}]", fontsize=10, loc='left', color='#333', fontweight='bold')

def plot_json_result(json_data):
    plt.rcParams['font.sans-serif'] = ['Arial Unicode MS', 'SimHei', 'DejaVu Sans']
    plt.rcParams['axes.unicode_minus'] = False
    if "contra_data" in json_data and json_data["contra_data"] is not None:
        fig, axes = plt.subplots(2, 1, figsize=(14, 10), sharex=True)
        side_main = json_data['contra_data'].get('side_main', '?')
        side_contra = json_data['contra_data'].get('side_contra', '?')
        plot_single(axes[0], json_data['signals'], json_data['events'], f"[{side_main}] 主测试侧")
        plot_single(axes[1], json_data['contra_data']['signals'], json_data['contra_data']['events'], f"[{side_contra}] 伴飞侧")
        axes[1].set_xlabel('时间 (s)', fontsize=9, fontweight='bold')
    else:
        fig, ax = plt.subplots(1, 1, figsize=(14, 5))
        plot_single(ax, json_data['signals'], json_data['events'], "测试脚")
        ax.set_xlabel('时间 (s)', fontsize=9, fontweight='bold')
        
    plt.tight_layout()
    plt.show()

def main():
    print("==== 步态算法 1:1 双脚/长跳/三级跳 管线端到端调试器 ====\n")
    
    parser = argparse.ArgumentParser(description='步态算法端到端测试可视化工具')
    parser.add_argument('files',      nargs='*', type=str, default=[], help='本地 CSV 文件路径')
    parser.add_argument('--start',    type=float, default=-1.0, help='裁剪开始时间 (s)')
    parser.add_argument('--end',      type=float, default=-1.0, help='裁剪结束时间 (s)')
    args = parser.parse_args()

    local_files = args.files

    if len(local_files) == 0:
        ans = input("请输入单个 CSV 路径（如有多文件填其一即可，另一脚会自动搜索），直接回车将使用 ADB 提取: ").strip()
        import shlex
        if ans:
            local_files = [f.strip('\'"') for f in shlex.split(ans)][:1]
        else:
            p = pick_file_from_device()
            if not p: return
            local_files = [p]

    main_file = local_files[0]
    if not os.path.exists(main_file):
        print(f"文件不存在: {main_file}")
        return

    # 询问高阶逻辑
    print("\n请选择跑哪种算法管线：")
    print("  0. 常规跑走 (不裁剪, 不清洗)")
    print("  1. 跳远单腿管线 (单侧切断 + 伴飞强同步)")
    print("  2. 三级跳双脚管线 (M型强干扰清洗)")
    mode = input("请输入数字 (0/1/2) [默认0]: ").strip()
    
    is_triple = False
    takeoff_step = -1
    is_l_takeoff = True
    
    is_main_takeoff_foot = False
    if mode == '2':
        is_triple = True
    elif mode == '1':
        step = input("请输入起跳步数 (如 3) [默认使用步频突降自动推断(0)]: ").strip()
        takeoff_step = int(step) if step else 0
        
        foot = input("主起跳脚是左脚吗？(y/n) [默认y]: ").strip().lower()
        is_l_takeoff = (foot != 'n')
        if ('7E6E' in main_file.upper() and is_l_takeoff) or ('937F' in main_file.upper() and not is_l_takeoff):
            is_main_takeoff_foot = True
        else:
            print("注意：你指定的起跳脚似乎不是当前你输入的主文件，算法作为伴飞侧启动，在同名文件夹搜索起跳脚...")
            
    print(f"\n正在以 App 100% 同款逻辑运算... 请稍后")
    
    # 调用核心包
    json_str = process_gait_data(
        file_path=main_file,
        weight_kg=75.0,
        start_time_s=args.start,
        end_time_s=args.end,
        long_jump_takeoff_step=takeoff_step,
        long_jump_is_takeoff_foot=is_main_takeoff_foot,
        is_triple_jump=is_triple
    )
    
    result = json.loads(json_str)
    
    if not result.get("ok", False):
        print("算法返回错误：", result.get("error"))
        return
        
    print("\n运算成功！处理耗时: {} ms".format(result.get('processing_time_ms', '?')))
    
    # 打印报表
    print_metrics(result.get('side_main', '?'), result.get('strides', []))
    if result.get("contra_data"):
        print_metrics(result.get("contra_data", {}).get("side_contra", "?"), result.get("contra_data", {}).get('strides', []))
        
    print("正在打开 matplotlib 图表 ... （关闭图表后脚本退出）")
    plot_json_result(result)

if __name__ == '__main__':
    main()
