#!/usr/bin/env python
# coding: utf-8

import json
import logging
import os
from datetime import datetime

import matplotlib.dates
import matplotlib.pyplot as plt
import numpy as np
import scipy.stats
from matplotlib.ticker import FuncFormatter

DATE_FORMAT = matplotlib.dates.DateFormatter('%H:%M:%S')
PLOT_PATH = os.path.join('report', 'sessions', 'plots', datetime.now().strftime(r'%Y-%m-%d'))
ECHO_PACKAGE_LENGTH_BYTES = 37


def to_percent(y, position):
    # Ignore the passed in position. This has the effect of scaling the default tick locations.
    s = str(100 * y)

    # The percent symbol needs escaping in latex
    if matplotlib.rcParams['text.usetex'] is True:
        return s + r'$\%$'
    else:
        return s + '%'


def perc_weights(data):
    return np.zeros_like(data) + 1 / data.size


percent_formatter = FuncFormatter(to_percent)


def read_codes(filename="codes.json"):
    with open(filename) as file_obj:
        logging.debug("Reading codes from file:%s.", filename)
        return json.load(file_obj)


def read_times(filename):
    with open(filename) as file_obj:
        file_obj.readline()  # throw away start time.
        lines = [line.split(":") for line in file_obj]
    times = np.fromiter((int(line[0]) for line in lines if line[2] != 'true'), dtype=int)
    return times


def plt_add_stats(mean, std, unit="ms"):
    plt.suptitle(r"$\mu$ =" + " {0:.1f} {unit}, $\sigma$ = {0:.2f} {unit}".format(mean, std, unit=unit))


def plt_save(filename, file_format='pdf'):
    filename = os.path.join(PLOT_PATH, filename + '.' + file_format)
    plt.savefig(filename=filename, format=file_format)


def plot_code(code):
    times = read_times(code + ".txt")
    # start_time = times[0]
    diffs = np.diff(times)
    mean = diffs.mean(dtype=int)
    std = diffs.std()
    times_d = [datetime.fromtimestamp(ms_time / 1000) for ms_time in times]
    # diffs_d = np.diff(times_d)

    throughput = {}
    for seconds_limit in (8, 16, 32):
        millis_limit = seconds_limit * 1000
        n_bins = int(np.ceil((times[-1] - times[0]) / millis_limit))
        x = np.zeros(n_bins, dtype=datetime)
        bins = np.zeros(n_bins)
        for limit in range(n_bins):
            start = np.searchsorted(times, times[0] + limit * millis_limit, 'left')
            end = np.searchsorted(times, times[0] + (limit + 1) * millis_limit, 'right')
            divisor = (times[min(end, len(times) - 1)] - times[start]) / 1000
            if divisor == 0:
                continue
            x[limit] = times_d[start]
            bins[limit] = (end - start) * ECHO_PACKAGE_LENGTH_BYTES / divisor
        throughput[seconds_limit] = x[x != 0], bins[bins != 0]

    figures = []  # Hold figures opened so we can save/close them after.

    def plot_throughput(limit, x, averages):
        mean = averages.mean()
        std = averages.std()

        figures.append(plt.figure())
        plt.plot_date(x, averages)
        ax = plt.gca()
        ax.xaxis.set_major_formatter(DATE_FORMAT)
        ax.yaxis.grid(True)
        plt.gcf().autofmt_xdate()
        plt.title("Ρυθμαπόδοση ανά {limit} seconds ({code})".format(code=code, limit=limit))
        plt_add_stats(mean, std, unit="bps")
        plt.xlabel("Χρόνος Άφιξης")
        plt.ylabel("Ρυθμός (bps)")
        plt.savefig(filename=os.path.join(PLOT_PATH, '{code}-lim{lim}.pdf'.format(code=code, lim=limit)), format='pdf')

    for key, value in throughput.items():
        plot_throughput(key, *value)

    figures.append(plt.figure())
    x = range(1, len(diffs) + 1)
    plt.scatter(x, diffs)
    ax = plt.gca()
    ax.yaxis.grid(True)
    plt.xlim(x[0] * 0.95, x[-1] * 1.01)
    plt.title("Χρόνος Απόκρισης για κάθε πακέτο ({code})".format(code=code))
    plt.xlabel("Αριθμός Πακέτου")
    plt.ylabel("Χρόνος Απόκρισης (ms)")
    plt_add_stats(mean, std)
    plt.savefig(filename=os.path.join(PLOT_PATH, '{code}-response-time.pdf'.format(code=code)), format='pdf')

    fig, ax = plt.subplots(1, 1)
    figures.append(fig)
    ax.hist(diffs, bins=10, weights=perc_weights(diffs), normed=False)
    if not code.endswith("0000"):
        # Plot normal distribution graph.
        x = np.linspace(*ax.get_xlim())
        rv = scipy.stats.norm(loc=mean, scale=std)
        ax.plot(x, rv.pdf(x) * 100, lw=2)
    ax.set_title("Συχνότητα χρόνου απόκρισης")
    ax.set_xlabel("Χρόνος Απόκρισης (ms)")
    ax.set_ylabel("Συχνότητα")
    ax.yaxis.set_major_formatter(percent_formatter)
    fig.savefig(filename=os.path.join(PLOT_PATH, '{code}-hist.pdf'.format(code=code)), format='pdf')

    # Histograms from throughput.
    for key, value in throughput.items():
        averages = value[1]
        figures.append(plt.figure())
        plt.hist(averages, bins=10, weights=perc_weights(averages), normed=False)
        plt.title("Συχνότητα throughput")
        plt.xlabel("Throughput")
        plt.ylabel("Συχνότητα")
        plt.gca().yaxis.set_major_formatter(percent_formatter)
        plt.savefig(filename=os.path.join(PLOT_PATH, '{code}-lim{lim}-hist.pdf'.format(code=code, lim=key)),
                    format='pdf')
    close_figures(figures)


def close_figures(figures):
    for figure in figures:
        plt.close(figure)


def plot_audio(code, texts, track_id=None, use_aq=False, n_packets=999, file_number=None, random_track=False):
    def open_audio_bytes(filename):
        with open(filename, 'rb') as file_obj:
            res = []
            size_to_read = 1
            byte = file_obj.read(size_to_read)
            while byte:
                res.append(int.from_bytes(byte, byteorder='big', signed=True))
                byte = file_obj.read(size_to_read)
        return np.array(res)

    def plt_wavelength(data, start=None, end=None, size=10000):
        def restore_size_format(y, position):
            # Ignore the passed in position. This has the effect of scaling the default tick locations.
            return str(int(multiplier * y))

        step_formatter = FuncFormatter(restore_size_format)

        data = data[start:end]
        limit = data.size // size * size
        data = np.reshape(data[:limit], (size, -1))
        # Multiplier is the automatic dimension.
        multiplier = data.shape[1]
        data = np.average(data, axis=1)
        figures.append(plt.figure())
        plt.gca().xaxis.set_major_formatter(step_formatter)
        plt.plot(data)
        plt.ylim([min(data), max(data)])

    def add_texts(plt_texts):
        for field, text in plt_texts.items():
            oldtext = text
            text = text.format(**formatter)
            while text != oldtext:
                oldtext = text
                text = text.format(**formatter)
            getattr(plt, field)(text)

    def plt_hist(data):
        figures.append(plt.figure())
        plt.hist(data, weights=perc_weights(data), normed=False)
        plt.gca().yaxis.set_major_formatter(percent_formatter)

    def plot_aq_stats():
        with open(base_filename + ".txt") as file_obj:
            stats = json.load(file_obj)
        for metric, y in stats.items():
            figures.append(plt.figure())
            plt.scatter(np.arange(len(y)), y)
            add_texts(texts[metric])
            plt_save(base_filename + "-" + metric)

    assert (track_id is not None) != random_track
    formatter = {
        'code': code,
        'track_id': ("L" + str(track_id).zfill(2)) * (not random_track),
        'aq_code': "AQ" * use_aq,
        'n_packets': n_packets,
        'date': "!#TODO#!",
        'aq': ", {aq_code}, " * use_aq,
        'file_number': ("-" + str(file_number)) * (file_number is not None),
        'track_type': "T" if random_track else "F",
        "track_info": "τυχαίο κομμάτι από γεννήρια (AQ)" if random_track else "τραγούδι {track_id}{aq}"
    }
    base_filename = "{code}{track_id}{aq_code}{track_type}{n_packets}".format(**formatter)
    logging.info("Base file:%s.", base_filename)

    figures = []

    decoded = open_audio_bytes(base_filename + 'decoded{file_number}.data'.format(**formatter))
    buffer = open_audio_bytes(base_filename + 'buffer{file_number}.data'.format(**formatter))

    plt_wavelength(buffer, size=10000)
    add_texts(texts['buffer'])
    plt_save(base_filename + '-buffer{file_number}'.format(**formatter))

    plt_wavelength(decoded, size=10000)
    add_texts(texts['decoded'])
    plt_save(base_filename + '-decoded{file_number}'.format(**formatter))

    plt_hist(decoded)
    add_texts(texts['decoded-hist'])
    plt_save(base_filename + '-decoded-hist{file_number}'.format(**formatter))

    if use_aq:
        plot_aq_stats()

    close_figures(figures)


# Init matplotlib & settings.
plt.rc('text', usetex=False)
plt.style.use('ggplot')
matplotlib.rc('font', family='Ubuntu')

os.makedirs(PLOT_PATH, exist_ok=True)
codes = read_codes()
texts_dpcm = {
    'buffer': {
        'title': "Κυματομορφή από την Ithaki: {track_info} ({code})",
        'xlabel': "Αριθμός δείγματος",
        'ylabel': "Τιμή"
    },
    'decoded': {
        'title': "Αποκωδικοποιημένη κυματομορφή: {track_info} ({code})",
        'xlabel': "Αριθμός δείγματος",
        'ylabel': "Τιμή"
    },
    'decoded-hist': {
        'title': "Κατανομή διαφορών δειγμάτων: {track_info} ({code})",
        'xlabel': "Τιμή",
        'ylabel': "Συχνότητα"
    },
    'mean': {
        'title': "Εξέλιξη μέσης τιμής: {track_info} ({code})",
        'xlabel': "Αριθμός πακέτου",
        'ylabel': r"Τιμή $\mu$"
    },
    'step': {
        'title': "Εξέλιξη βήματος: {track_info} ({code})",
        'xlabel': "Αριθμός πακέτου",
        'ylabel': r"Τιμή $\beta$"
    }
}

plot_code('E0000')
plot_code(codes['echoRequestCode'])
plot_audio(codes['soundRequestCode'], track_id=10, use_aq=False, texts=texts_dpcm)
plot_audio(codes['soundRequestCode'], track_id=23, use_aq=True, texts=texts_dpcm)
plot_audio(codes['soundRequestCode'], track_id=23, use_aq=True, texts=texts_dpcm)
plot_audio(codes['soundRequestCode'], use_aq=True, random_track=True, texts=texts_dpcm)
plot_audio(codes['soundRequestCode'], use_aq=True, random_track=True, texts=texts_dpcm, file_number=1)
