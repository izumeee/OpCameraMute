# ReleaseではR8による縮小・最適化・難読化を有効にする。
# AudioPolicyUserServiceと公開引数なしコンストラクターはソースの@Keepで保持する。
# Shizukuが別プロセスからリフレクションで生成する入口のため、削除・改名しない。
# NamedAudioSystemAdapterが名前で参照するandroid.media.AudioSystemはOS側クラスであり、
# 本アプリのR8処理対象外。パッケージ全体を保持するルールは追加しない。
