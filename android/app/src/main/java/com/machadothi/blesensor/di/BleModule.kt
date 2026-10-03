package com.machadothi.blesensor.di

import com.machadothi.blesensor.repository.BoardRepository
import com.machadothi.blesensor.repository.BoardRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BleModule {

    @Binds
    abstract fun bindsBoardRepository(impl: BoardRepositoryImpl): BoardRepository
}
